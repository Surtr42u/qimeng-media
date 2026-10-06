/**
 * 视频播放器封装（W-3；ArtPlayer 官方文档 https://artplayer.org/document，v5.4.0）。
 *
 * 职责边界：纯 UI 组件——不发任何网络请求（铁律 7）；断点续播起点与进度上报由
 * AssetDetailPage 经 hooks（use-progress.ts）以 props 注入。
 * 生命周期：实例在 effect 内创建、清理函数 destroy（官方卸载语义）；回调经 ref
 * 转发，props 刷新不重建播放器实例。
 *
 * 控制条与内核收敛（统一 Web 架构）：
 * 浏览器端与桌面壳（Tauri）统一使用 ArtPlayer + PlayerControls 纯 Web 播放器。
 * 彻底告别 Win32 子窗口 Airspace 冲突（遮挡顶栏、换页残留、视频上移跳动），
 * 纯白卡片浮层自由悬停，两端代码 100% 收敛。
 */

import Artplayer from 'artplayer'
import { useCallback, useEffect, useRef, useState } from 'react'
import { stepPlayGate, type PlayGateState } from '@/lib/engagement-reporting'
import { PLAYBACK_RATES } from '@/lib/player-labels'
import PlayerControls, {
  type PlayerMediaInfo,
  type PlayerSubtitleTrack,
  type TrayKind,
} from './player-controls'

/** 控制条无操作自动隐藏时长（毫秒） */
const WEB_CONTROLS_AUTOHIDE_MS = 2500

/** 进度条打点 */
export interface PlayerHighlight {
  time: number
  text: string
}

export interface VideoPlayerProps {
  /** 视频签名直链（detail.origUrl） */
  src: string
  poster?: string
  /** 断点续播起点（秒；已看完场景由页面推导传 0） */
  startTime?: number
  /** 时间轴标签打点 */
  highlights?: PlayerHighlight[]
  /** 播放中心跳（页面侧经 useProgress 5s 节流上报） */
  onTimeUpdate?: (positionSeconds: number) => void
  /** 暂停（页面侧立即上报） */
  onPause?: (positionSeconds: number) => void
  /** 起播（页面侧上报 play 事件，口径 B 防重闸门） */
  onPlay?: () => void
  /** 原生内核播放窗标题（兼容保留） */
  nativeTitle?: string
}

/** 运行时读主题 token */
function readPrimaryColor(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--qm-primary').trim()
}

/** 若处于 Tauri 壳中且曾经开启过 mpv，做一次性安全关停，防孤儿窗残留 */
function closeOrphanMpvInShell(): void {
  if (typeof window !== 'undefined' && '__TAURI__' in window) {
    const t = (window as unknown as {
      __TAURI__?: {
        core?: {
          invoke?: (cmd: string, args?: Record<string, unknown>) => Promise<unknown>
        }
      }
    }).__TAURI__
    void t?.core?.invoke?.('mpv_stage_rect', { x: 0, y: 0, w: 0, h: 0, docW: 1, visible: false }).catch(() => {})
    void t?.core?.invoke?.('mpv_close').catch(() => {})
  }
}

export default function VideoPlayer({
  src,
  poster,
  startTime = 0,
  highlights = [],
  onTimeUpdate,
  onPause,
  onPlay,
}: VideoPlayerProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [initial] = useState({ src, poster, startTime, highlights })
  const handlersRef = useRef({ onTimeUpdate, onPause, onPlay })
  useEffect(() => {
    handlersRef.current = { onTimeUpdate, onPause, onPlay }
  })
  const playGateRef = useRef<PlayGateState>('idle')

  // 安全防线：首次挂载时如果处于 Tauri 壳中，确保关闭任何残留的底层 mpv 实例
  useEffect(() => {
    closeOrphanMpvInShell()
  }, [])

  // —— 统一 Web 播放器状态（ArtPlayer + 共用 PlayerControls） ——
  const artInstanceRef = useRef<Artplayer | null>(null)
  const [webReady, setWebReady] = useState(false)
  const [webDuration, setWebDuration] = useState(0)
  const [webPosition, setWebPosition] = useState(0)
  const [webPaused, setWebPaused] = useState(true)
  const [webSpeed, setWebSpeed] = useState(1)
  const [webVolume, setWebVolume] = useState(70)
  const [webMuted, setWebMuted] = useState(true)
  const [webFullscreen, setWebFullscreen] = useState(false)
  const [webAutohide, setWebAutohide] = useState(false)
  const [webOpenTray, setWebOpenTray] = useState<TrayKind | null>(null)
  const autohideTimerRef = useRef<number | null>(null)

  const [webMedia, setWebMedia] = useState<PlayerMediaInfo>({
    width: 0,
    height: 0,
    codec: '',
    fps: 0,
    aspect: 'no',
    loopFile: false,
    sid: 0,
    tracks: [],
  })

  // 控制条自动隐藏逻辑：播放中且无操作 2.5s 后隐藏，展开托盘菜单或暂停态恒不隐藏
  const triggerActivity = useCallback(() => {
    setWebAutohide(false)
    if (autohideTimerRef.current !== null) {
      window.clearTimeout(autohideTimerRef.current)
      autohideTimerRef.current = null
    }
    if (!webPaused && webOpenTray === null) {
      autohideTimerRef.current = window.setTimeout(() => {
        setWebAutohide(true)
      }, WEB_CONTROLS_AUTOHIDE_MS)
    }
  }, [webPaused, webOpenTray])

  const handleBrowserMouseMove = useCallback(() => {
    triggerActivity()
  }, [triggerActivity])

  const handleBrowserMouseLeave = useCallback(() => {
    if (autohideTimerRef.current !== null) {
      window.clearTimeout(autohideTimerRef.current)
      autohideTimerRef.current = null
    }
    if (!webPaused && webOpenTray === null) {
      setWebAutohide(true)
    }
  }, [webPaused, webOpenTray])

  useEffect(() => {
    if (webPaused || webOpenTray !== null) {
      if (autohideTimerRef.current !== null) {
        window.clearTimeout(autohideTimerRef.current)
        autohideTimerRef.current = null
      }
      setWebAutohide((prev) => (prev ? false : prev))
    }
    return () => {
      if (autohideTimerRef.current !== null) {
        window.clearTimeout(autohideTimerRef.current)
      }
    }
  }, [webPaused, webOpenTray])

  // ArtPlayer 初始化与生命周期
  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    Artplayer.PLAYBACK_RATE = [...PLAYBACK_RATES]
    // 监听视频元数据更新媒体信息（分辨率、轨道）
    const updateMediaMetadata = () => {
      const v = artInstanceRef.current?.video
      if (!v) return
      const tracks: PlayerSubtitleTrack[] = []
      if (v.textTracks) {
        for (let i = 0; i < v.textTracks.length; i++) {
          const t = v.textTracks[i]
          tracks.push({
            id: i + 1,
            title: t.label || `轨道 ${i + 1}`,
            lang: t.language || '',
            external: false,
          })
        }
      }
      setWebMedia((prev) => ({
        ...prev,
        width: v.videoWidth || prev.width,
        height: v.videoHeight || prev.height,
        tracks,
      }))
    }

    const onArtReady = (instance: Artplayer) => {
      setWebReady(true)
      setWebDuration(instance.duration)
      updateMediaMetadata()
      if (initial.startTime > 0) instance.seek = initial.startTime
    }

    const art = new Artplayer(
      {
        container,
        url: initial.src,
        poster: initial.poster,
        theme: readPrimaryColor(),
        lang: 'zh-cn',
        setting: false, // 设置面板与倍速完全由自绘控制条接管
        playbackRate: false,
        fullscreen: false,
        fullscreenWeb: true,
        highlight: initial.highlights,
        muted: true,
        controls: [], // 隐藏官方自带底栏，统一使用 PlayerControls
      },
      onArtReady,
    )
    artInstanceRef.current = art

    art.on('ready', () => onArtReady(art))

    art.on('video:loadedmetadata', () => {
      setWebReady(true)
      setWebDuration(art.duration)
      updateMediaMetadata()
    })

    art.on('video:canplay', () => {
      setWebReady(true)
    })

    art.on('video:timeupdate', () => {
      setWebPosition(art.currentTime)
      handlersRef.current.onTimeUpdate?.(art.currentTime)
    })

    art.on('video:durationchange', () => {
      setWebDuration(art.duration)
    })

    art.on('video:ratechange', () => {
      setWebSpeed(art.playbackRate)
    })

    art.on('video:volumechange', () => {
      setWebVolume(Math.round(art.volume * 100))
      setWebMuted(art.muted)
    })

    art.on('fullscreenWeb', (state: boolean) => {
      setWebFullscreen(state)
    })

    art.on('pause', () => {
      setWebPaused(true)
      playGateRef.current = stepPlayGate(playGateRef.current, 'pause').state
      handlersRef.current.onPause?.(art.currentTime)
    })

    const handlePlaySignal = () => {
      setWebPaused(false)
      const step = stepPlayGate(playGateRef.current, 'play')
      playGateRef.current = step.state
      if (step.report) handlersRef.current.onPlay?.()
    }
    const handlePauseReset = () => {
      setWebPaused(true)
      playGateRef.current = stepPlayGate(playGateRef.current, 'pause').state
    }
    art.on('play', handlePlaySignal)
    art.on('video:play', handlePlaySignal)
    art.on('video:pause', handlePauseReset)

    // 深色模式跟随 token
    const themeObserver = new MutationObserver(() => {
      art.theme = readPrimaryColor()
    })
    themeObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    })

    return () => {
      themeObserver.disconnect()
      art.destroy()
      artInstanceRef.current = null
    }
  }, [
    initial.src,
    initial.poster,
    initial.startTime,
    initial.highlights,
  ])

  // 画面比例调节
  const handleWebAspect = (aspect: string) => {
    const art = artInstanceRef.current
    if (!art?.video) return
    const v = art.video
    if (aspect === 'fill') {
      v.style.objectFit = 'fill'
    } else if (aspect === '16:9' || aspect === '4:3') {
      v.style.objectFit = 'contain'
    } else {
      v.style.objectFit = 'contain'
    }
    setWebMedia((prev) => ({ ...prev, aspect }))
  }

  // 单片循环
  const handleWebLoop = (loop: boolean) => {
    const art = artInstanceRef.current
    if (art?.video) art.video.loop = loop
    setWebMedia((prev) => ({ ...prev, loopFile: loop }))
  }

  // 字幕切换
  const handleWebSubtitle = (sid: number) => {
    const art = artInstanceRef.current
    if (!art?.video?.textTracks) return
    const tracks = art.video.textTracks
    for (let i = 0; i < tracks.length; i++) {
      tracks[i].mode = sid === i + 1 ? 'showing' : 'hidden'
    }
    setWebMedia((prev) => ({ ...prev, sid }))
  }

  return (
    <div
      className={`browser-player-wrap${webFullscreen ? ' fullscreen' : ''}`}
      onMouseMove={handleBrowserMouseMove}
      onMouseEnter={handleBrowserMouseMove}
      onMouseLeave={handleBrowserMouseLeave}
    >
      <div className="video-player-box" ref={containerRef} />
      <PlayerControls
        duration={webDuration}
        position={webPosition}
        paused={webPaused}
        speed={webSpeed}
        volume={webVolume}
        muted={webMuted}
        highlights={initial.highlights}
        ready={webReady}
        fullscreen={webFullscreen}
        media={webMedia}
        can={{
          quality: true,
          speed: true,
          subtitles: true,
          settings: true,
          aspect: true,
          loop: true,
          plugins: true,
          maxVolume: 100,
        }}
        autohide={webAutohide}
        onTrayChange={setWebOpenTray}
        onTogglePause={() => {
          const art = artInstanceRef.current
          if (!art) return
          const v = art.video
          if (v) {
            if (v.paused) {
              void v.play().catch(() => art.play())
            } else {
              v.pause()
            }
          } else {
            art.toggle()
          }
        }}
        onSeek={(s) => {
          if (artInstanceRef.current) artInstanceRef.current.seek = s
        }}
        onSpeed={(r) => {
          if (artInstanceRef.current) artInstanceRef.current.playbackRate = r
        }}
        onVolume={(v) => {
          const art = artInstanceRef.current
          if (art) {
            art.volume = Math.min(1, Math.max(0, v / 100))
            if (v > 0) art.muted = false
          }
        }}
        onMute={(m) => {
          if (artInstanceRef.current) artInstanceRef.current.muted = m
        }}
        onToggleFullscreen={() => {
          if (artInstanceRef.current) {
            artInstanceRef.current.fullscreenWeb = !artInstanceRef.current.fullscreenWeb
          }
        }}
        onSubtitle={handleWebSubtitle}
        onAspect={handleWebAspect}
        onLoopFile={handleWebLoop}
      />
    </div>
  )
}
