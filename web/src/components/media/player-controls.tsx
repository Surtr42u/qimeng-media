/**
 * 播放器自绘控制条 —— 网页端（ArtPlayer）与桌面端（Tauri + mpv）共用组件。
 *
 * 视觉规范：按 ArtPlayer 实测度量与用户参照图逐项复刻，并融合 Aurora Glass 质感：
 *   - 控制行 46px（全屏 60px）· 图标按钮 46×46 · 图标盒 36px 且常态 scale(1.1)
 *   - 底部 100px 柔和暗色遮罩渐变：linear-gradient(to top, rgba(0,0,0,.85), rgba(0,0,0,.4) 50%, transparent)
 *   - 右起五入口：清晰度（原件真实分辨率）· 倍速（顶层独立）· 字幕 · 设置 · 网页全屏
 *   - 纯白高精文本（主字 600/700，副标 400 12px，对齐用户参照图）
 *   - 托盘（Tray）：横向紧凑单行，40px 高，半透明毛玻璃底衬 + 发丝描边，支持水平横向滚动
 *
 * 铁律 7：本组件纯 UI（无直接 API 调用、无 IPC、无业务规则）——播控一律经 props 回调交给父级。
 * 键盘防抖：所有按钮标注 tabIndex={-1}，防止点击后获焦导致空格暂停键误触原生按钮点击。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import {
  PLAYBACK_RATES,
  codecLabel,
  formatTime,
  qualityLabel,
  rateLabel,
  subtitleBadge,
  subtitleLabel,
} from '@/lib/player-labels'
import {
  IconCheck,
  IconFullscreenWebOff,
  IconFullscreenWebOn,
  IconPause,
  IconPlay,
  IconSetting,
  IconVolume,
  IconVolumeClose,
} from './player-icons'
import type { PlayerHighlight } from './video-player'

/** 托盘种类（同时只开一个；再点同一个 = 收起） */
export type TrayKind = 'quality' | 'rate' | 'subtitle' | 'settings'

/** 一条字幕轨 */
export interface PlayerSubtitleTrack {
  id: number
  title: string
  lang: string
  external: boolean
}

/** 媒体信息（清晰度/字幕/设置三入口的数据源） */
export interface PlayerMediaInfo {
  width: number
  height: number
  codec: string
  fps: number
  aspect: string
  loopFile: boolean
  /** 当前字幕轨 id（0=关闭） */
  sid: number
  tracks: PlayerSubtitleTrack[]
}

/** 控制条引擎能力声明 */
export interface PlayerControlsCapabilities {
  /** 是否展示清晰度入口（默认 true） */
  quality?: boolean
  /** 是否展示倍速入口（默认 true） */
  speed?: boolean
  /** 是否展示字幕入口（默认 true） */
  subtitles?: boolean
  /** 是否展示设置按钮（默认 true） */
  settings?: boolean
  /** 设置托盘内是否支持画面比例调节（默认 true） */
  aspect?: boolean
  /** 设置托盘内是否支持单片循环（默认 true） */
  loop?: boolean
  /** 设置托盘内是否显示插件占位（默认 true） */
  plugins?: boolean
  /** 音量上限，mpv 为 130，web video 为 100（默认 100） */
  maxVolume?: number
}

export interface PlayerControlsProps {
  /** 总时长（秒；未知=0） */
  duration: number
  /** 当前播放位置（秒） */
  position: number
  paused: boolean
  speed: number
  /** 音量（0~maxVolume） */
  volume: number
  muted: boolean
  /** 时间轴打点 */
  highlights?: PlayerHighlight[]
  /** 播放器是否就绪（未就绪时控件禁用） */
  ready: boolean
  /** 是否网页全屏态（铺满窗口） */
  fullscreen: boolean
  /** 媒体信息 */
  media: PlayerMediaInfo
  /** 引擎能力声明（不支持的项在 UI 上优雅隐藏） */
  can?: PlayerControlsCapabilities
  /** 是否自动隐藏控制条（由父组件的无操作检测控制） */
  autohide?: boolean
  /** 托盘开关变化回调（通知父组件托盘展开状态，用于锁定自动隐藏） */
  onTrayChange?: (openTray: TrayKind | null) => void
  onTogglePause: () => void
  onSeek: (seconds: number) => void
  onSpeed: (rate: number) => void
  onVolume: (volume: number) => void
  onMute: (muted: boolean) => void
  onToggleFullscreen: () => void
  /** 字幕轨选择（0=关闭） */
  onSubtitle: (sid: number) => void
  /** 画面比例（白名单字面量：'no' | '16:9' | '4:3' | 'fill'） */
  onAspect: (value: string) => void
  /** 单片循环 */
  onLoopFile: (on: boolean) => void
}

export default function PlayerControls({
  duration,
  position,
  paused,
  speed,
  volume,
  muted,
  highlights = [],
  ready,
  fullscreen,
  media,
  can = {},
  autohide = false,
  onTrayChange,
  onTogglePause,
  onSeek,
  onSpeed,
  onVolume,
  onMute,
  onToggleFullscreen,
  onSubtitle,
  onAspect: _onAspect,
  onLoopFile: _onLoopFile,
}: PlayerControlsProps) {
  const [tray, setTray] = useState<TrayKind | null>(null)
  const [dragging, setDragging] = useState(false)
  const [hoverRatio, setHoverRatio] = useState<number | null>(null)
  const progressRef = useRef<HTMLDivElement>(null)

  const maxVolume = can.maxVolume ?? 100
  const canQuality = can.quality ?? true
  const canSpeed = can.speed ?? true
  const canSubtitles = can.subtitles ?? true
  const canSettings = can.settings ?? true

  // 取消静音时恢复的默认音量
  const lastAudibleRef = useRef(volume > 0 ? volume : Math.min(70, maxVolume))

  const ratio = duration > 0 ? Math.min(1, Math.max(0, position / duration)) : 0
  const percent = `${(ratio * 100).toFixed(3)}%`
  const quality = qualityLabel(media.height)
  const subBadge = subtitleBadge(media.tracks, media.sid)
  const displayVolume = muted ? 0 : volume

  /** 清晰度托盘的信息行（原件真实参数） */
  const mediaNote = useMemo(() => {
    const parts: string[] = []
    if (media.width > 0 && media.height > 0) parts.push(`${media.width}×${media.height}`)
    const codec = codecLabel(media.codec)
    if (codec) parts.push(codec)
    if (media.fps > 0) parts.push(`${media.fps.toFixed(media.fps % 1 === 0 ? 0 : 2)}fps`)
    return parts.join(' · ')
  }, [media.width, media.height, media.codec, media.fps])

  /** 打点位置（百分比） */
  const marks = useMemo(() => {
    if (!(duration > 0)) return []
    return highlights
      .filter((h) => Number.isFinite(h.time) && h.time > 0 && h.time < duration)
      .map((h) => ({ left: `${((h.time / duration) * 100).toFixed(3)}%`, text: h.text, time: h.time }))
  }, [highlights, duration])

  /** 指针 x 坐标 → 进度比例（0~1） */
  const ratioFromClientX = useCallback(
    (clientX: number): number | null => {
      const el = progressRef.current
      if (!el || !(duration > 0)) return null
      const rect = el.getBoundingClientRect()
      if (rect.width <= 0) return null
      return Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
    },
    [duration],
  )

  const seekFromClientX = useCallback(
    (clientX: number) => {
      const r = ratioFromClientX(clientX)
      if (r === null) return
      onSeek(r * duration)
    },
    [ratioFromClientX, duration, onSeek],
  )

  const toggleMute = () => {
    if (muted || volume <= 0) {
      onVolume(lastAudibleRef.current || Math.min(70, maxVolume))
      onMute(false)
    } else {
      lastAudibleRef.current = volume
      onMute(true)
    }
  }

  const [volDragging, setVolDragging] = useState(false)
  const volumeTrackRef = useRef<HTMLDivElement>(null)

  const setVolumeFromClientY = useCallback(
    (clientY: number) => {
      const track = volumeTrackRef.current
      if (!track) return
      const rect = track.getBoundingClientRect()
      if (rect.height <= 0) return
      const ratio = Math.min(1, Math.max(0, (rect.bottom - clientY) / rect.height))
      const next = Math.round(ratio * maxVolume)
      if (next > 0) lastAudibleRef.current = next
      onMute(false)
      onVolume(next)
    },
    [maxVolume, onMute, onVolume],
  )

  const handleVolumeWheel = useCallback(
    (e: React.WheelEvent) => {
      e.preventDefault()
      const delta = e.deltaY < 0 ? 5 : -5
      const cur = muted ? 0 : volume
      const next = Math.min(maxVolume, Math.max(0, Math.round(cur + delta)))
      if (next > 0) lastAudibleRef.current = next
      onMute(false)
      onVolume(next)
    },
    [maxVolume, muted, onMute, onVolume, volume],
  )

  const toggleTray = (kind: TrayKind) => {
    setTray((cur) => {
      const next = cur === kind ? null : kind
      onTrayChange?.(next)
      return next
    })
  }

  const closeTray = () => {
    setTray(null)
    onTrayChange?.(null)
  }

  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!tray) return
    const handlePointerDown = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setTray(null)
        onTrayChange?.(null)
      }
    }
    document.addEventListener('pointerdown', handlePointerDown)
    return () => document.removeEventListener('pointerdown', handlePointerDown)
  }, [tray, onTrayChange])

  const volumePercent = maxVolume > 0 ? Math.min(100, Math.max(0, (displayVolume / maxVolume) * 100)) : 0

  return (
    <div
      ref={rootRef}
      className={`player-controls native-controls${fullscreen ? ' fullscreen' : ''}${
        autohide && !tray ? ' autohide' : ''
      }`}
    >
      {/* 竖向菜单托盘行：在 DOM 正常流中，位于进度条上方。
          在桌面壳内（Tauri + mpv）正常占高，驱动 native-stage-video 自动缩放 HWND 矩形，
          彻底解决绝对定位向上悬浮被 Win32 mpv 原生子窗遮挡的问题；
          在浏览器模式下绝对定位容器内自底向上展开，自然悬浮画面底部。 */}
      {tray ? (
        <div className="nc-vertical-tray-row">
          <div className={`nc-menu-popover nc-popover-${tray}`} role="menu" aria-label="播放器控制菜单">
            {tray === 'quality' && canQuality ? (
              <>
                <div className="nc-menu-title">清晰度</div>
                <div className="nc-menu-list">
                  <button
                    type="button"
                    className="nc-menu-item active"
                    onClick={closeTray}
                    tabIndex={-1}
                  >
                    <span className="nc-menu-label">原画{quality.main ? ` · ${quality.main}` : ''}</span>
                    <span className="nc-menu-check">
                      <IconCheck />
                    </span>
                  </button>
                </div>
                {mediaNote ? <div className="nc-menu-note">{mediaNote}</div> : null}
              </>
            ) : null}

            {tray === 'rate' && canSpeed ? (
              <>
                <div className="nc-menu-title">播放速度</div>
                <div className="nc-menu-list">
                  {[...PLAYBACK_RATES].reverse().map((rate) => {
                    const isSel = Math.abs(rate - speed) < 0.001
                    return (
                      <button
                        key={rate}
                        type="button"
                        className={`nc-menu-item${isSel ? ' active' : ''}`}
                        tabIndex={-1}
                        onClick={() => {
                          onSpeed(rate)
                          closeTray()
                        }}
                      >
                        <span className="nc-menu-label">{rateLabel(rate)}</span>
                        {isSel ? (
                          <span className="nc-menu-check">
                            <IconCheck />
                          </span>
                        ) : null}
                      </button>
                    )
                  })}
                </div>
              </>
            ) : null}

            {tray === 'subtitle' && canSubtitles ? (
              <>
                <div className="nc-menu-title">字幕</div>
                <div className="nc-menu-list">
                  <button
                    type="button"
                    className={`nc-menu-item${media.sid <= 0 ? ' active' : ''}`}
                    tabIndex={-1}
                    onClick={() => {
                      onSubtitle(0)
                      closeTray()
                    }}
                  >
                    <span className="nc-menu-label">关闭</span>
                    {media.sid <= 0 ? (
                      <span className="nc-menu-check">
                        <IconCheck />
                      </span>
                    ) : null}
                  </button>
                  {media.tracks.map((t) => {
                    const isSel = media.sid === t.id
                    return (
                      <button
                        key={t.id}
                        type="button"
                        className={`nc-menu-item${isSel ? ' active' : ''}`}
                        tabIndex={-1}
                        onClick={() => {
                          onSubtitle(t.id)
                          closeTray()
                        }}
                        title={[subtitleLabel(t), t.lang, t.external ? '外挂' : '内嵌'].filter(Boolean).join(' · ')}
                      >
                        <span className="nc-menu-label">
                          {subtitleLabel(t)}
                          {t.external ? <span className="nc-menu-tag">外挂</span> : null}
                        </span>
                        {isSel ? (
                          <span className="nc-menu-check">
                            <IconCheck />
                          </span>
                        ) : null}
                      </button>
                    )
                  })}
                </div>
                {media.tracks.length === 0 ? (
                  <div className="nc-menu-note">无可用字幕轨</div>
                ) : null}
              </>
            ) : null}

            {tray === 'settings' && canSettings ? (
              <>
                <div className="nc-menu-title">设置</div>
                <div className="nc-menu-note">插件位 · 待接入</div>
              </>
            ) : null}
          </div>
        </div>
      ) : null}

      {/* 进度条（padding 10px 0 5px = 21px；内层 6px 轨道带） */}
      <div
        className={`nc-progress${dragging ? ' dragging' : ''}`}
        ref={progressRef}
        onPointerDown={(e) => {
          if (!ready) return
          e.currentTarget.setPointerCapture(e.pointerId)
          setDragging(true)
          seekFromClientX(e.clientX)
        }}
        onPointerMove={(e) => {
          setHoverRatio(ratioFromClientX(e.clientX))
          if (dragging) seekFromClientX(e.clientX)
        }}
        onPointerUp={(e) => {
          if (dragging) seekFromClientX(e.clientX)
          setDragging(false)
          e.currentTarget.releasePointerCapture?.(e.pointerId)
        }}
        onPointerLeave={() => setHoverRatio(null)}
      >
        <div className="nc-band">
          <div className="nc-track" />
          <div className="nc-played" style={{ width: percent }} />
          {marks.map((m) => (
            <span
              key={`${m.time}-${m.text}`}
              className="nc-mark"
              style={{ left: m.left }}
              title={`${formatTime(m.time)} ${m.text}`}
            />
          ))}
          <span className="nc-thumb" style={{ left: percent }} />
        </div>
        {hoverRatio !== null ? (
          <span className="nc-preview" style={{ left: `${(hoverRatio * 100).toFixed(3)}%` }}>
            {formatTime(hoverRatio * duration)}
          </span>
        ) : null}
      </div>

      {/* 控制行：左组（播放/主流音量滑条/时间居中）+ 右组（清晰度/倍速/字幕/设置/全屏） */}
      <div className="nc-row">
        <div className="nc-group">
          {/* 播放/暂停按钮：随时可响应起播 */}
          <button
            type="button"
            className="nc-btn"
            onClick={onTogglePause}
            tabIndex={-1}
            aria-label={paused ? '播放' : '暂停'}
            title={paused ? '播放' : '暂停'}
          >
            <span className="nc-icon">{paused ? <IconPlay /> : <IconPause />}</span>
          </button>

          {/* 音量控制（主流竖向卡片交互）：悬停向上弹出竖向音量卡片，滚轮亦可平滑微调 */}
          <div
            className={`nc-volume-wrap${volDragging ? ' dragging' : ''}`}
            onWheel={handleVolumeWheel}
          >
            <div className="nc-volume-panel" role="group" aria-label="音量控制">
              <span className="nc-volume-val">{Math.round(displayVolume)}</span>
              <div
                className="nc-volume-track"
                ref={volumeTrackRef}
                tabIndex={-1}
                onPointerDown={(e) => {
                  e.currentTarget.setPointerCapture(e.pointerId)
                  setVolDragging(true)
                  setVolumeFromClientY(e.clientY)
                }}
                onPointerMove={(e) => {
                  if (volDragging) setVolumeFromClientY(e.clientY)
                }}
                onPointerUp={(e) => {
                  if (volDragging) {
                    setVolumeFromClientY(e.clientY)
                    setVolDragging(false)
                    e.currentTarget.releasePointerCapture?.(e.pointerId)
                  }
                }}
                onPointerCancel={(e) => {
                  setVolDragging(false)
                  e.currentTarget.releasePointerCapture?.(e.pointerId)
                }}
              >
                <div className="nc-volume-bar">
                  <div className="nc-volume-fill" style={{ height: `${volumePercent}%` }} />
                  <div className="nc-volume-thumb" style={{ bottom: `${volumePercent}%` }} />
                </div>
              </div>
            </div>

            <button
              type="button"
              className="nc-btn nc-volume-btn"
              onClick={toggleMute}
              tabIndex={-1}
              aria-label={muted || displayVolume <= 0 ? '取消静音' : '静音'}
              title={muted || displayVolume <= 0 ? '取消静音' : '静音'}
            >
              <span className="nc-icon">
                {displayVolume <= 0 ? <IconVolumeClose /> : <IconVolume />}
              </span>
            </button>
          </div>

          {/* 时间：严格垂直居中，纯白高对比度，数字等宽防抖 */}
          <span className="nc-time">
            {formatTime(position)} / {formatTime(duration)}
          </span>
        </div>

        <div className="nc-group nc-group-right">
          {/* 清晰度 */}
          {canQuality ? (
            <button
              type="button"
              className={`nc-text-btn${tray === 'quality' ? ' active' : ''}`}
              onClick={() => toggleTray('quality')}
              disabled={!ready}
              tabIndex={-1}
              title={mediaNote ? `清晰度：原画 ${mediaNote}` : '清晰度：原画'}
            >
              {quality.main ? (
                <>
                  <span className="nc-text-main">{quality.main}</span>
                  {quality.tag ? <span className="nc-text-sub">{quality.tag}</span> : null}
                </>
              ) : (
                <span className="nc-text-main">清晰度</span>
              )}
            </button>
          ) : null}

          {/* 倍速 */}
          {canSpeed ? (
            <button
              type="button"
              className={`nc-text-btn${tray === 'rate' ? ' active' : ''}`}
              onClick={() => toggleTray('rate')}
              disabled={!ready}
              tabIndex={-1}
              title={`播放速度（当前 ${rateLabel(speed)}）`}
            >
              <span className="nc-text-main">倍速</span>
              {Math.abs(speed - 1) >= 0.001 ? (
                <span className="nc-text-sub">{rateLabel(speed)}</span>
              ) : null}
            </button>
          ) : null}

          {/* 字幕 */}
          {canSubtitles ? (
            <button
              type="button"
              className={`nc-text-btn${tray === 'subtitle' ? ' active' : ''}${
                media.sid > 0 ? ' on' : ''
              }`}
              onClick={() => toggleTray('subtitle')}
              disabled={!ready}
              tabIndex={-1}
              title={media.sid > 0 ? `当前字幕：${subBadge}` : '字幕'}
            >
              <span className="nc-text-main">
                {media.sid > 0 ? (subBadge || '已开启') : '字幕'}
              </span>
            </button>
          ) : null}

          {/* 设置 */}
          {canSettings ? (
            <button
              type="button"
              className={`nc-btn${tray === 'settings' ? ' active' : ''}`}
              onClick={() => toggleTray('settings')}
              disabled={!ready}
              tabIndex={-1}
              aria-label="设置"
              title="设置"
            >
              <span className="nc-icon">
                <IconSetting />
              </span>
            </button>
          ) : null}

          {/* 网页全屏 */}
          <button
            type="button"
            className="nc-btn"
            onClick={onToggleFullscreen}
            disabled={!ready}
            tabIndex={-1}
            aria-label={fullscreen ? '退出全屏' : '全屏'}
            title={fullscreen ? '退出全屏（Esc）' : '全屏'}
          >
            <span className="nc-icon">
              {fullscreen ? <IconFullscreenWebOff /> : <IconFullscreenWebOn />}
            </span>
          </button>
        </div>
      </div>
    </div>
  )
}

/** 兼容旧版名称别名导出 */
export { PlayerControls as NativeControls }
export type { PlayerControlsProps as NativeControlsProps }
