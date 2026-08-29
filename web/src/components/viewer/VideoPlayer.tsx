/**
 * 视频播放器（详情页播放态，全屏媒体区）。
 *
 * 交互规格要点：
 * - <video> 用 origUrl 签名直链（服务器已支持 Range，永不转码），preload="metadata"；
 * - 单击画面 = 播放/暂停；双击 = 全屏（Fullscreen API toggle）；
 * - 控制栏：播放/暂停、自绘进度条（点击 seek + 拖拽，显示已缓冲）、时间（lib/format.formatDuration）、
 *   倍速（0.5/1/1.5/2 Popover）、静音（默认静音！取消静音记住 localStorage 'qm_video_muted'）、全屏；
 * - 控制栏 5s 无操作自动隐藏（触摸/鼠标活动重置计时；暂停时不隐藏）；
 * - 同源续播：localStorage 'qm_resume_'+assetId 存秒数，挂载即 seek（currentTime < duration-5s 才恢复），
 *   播放中节流 3s 写；播放结束/退出清 key（≥95% 视为结束）；
 * - 第一次播放上报 useReportView.play（ref 幂等保护，同一会话只报一次）；
 * - 时间轴标签条（TimelineBar）在控制栏上方。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Maximize,
  Minimize,
  Pause,
  Play,
  Volume2,
  VolumeX,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useReportView } from '@/hooks/use-asset-detail'
import { formatDuration } from '@/lib/format'
import type { AssetDetail } from '@/api/generated'
import { TimelineBar } from './TimelineBar'

/* ------------------------------ 常量（交互规格来源） ------------------------------ */

/** 控制栏自动隐藏：无操作 5s（ms）——交互规格定值；暂停状态不隐藏 */
const CONTROLS_HIDE_DELAY_MS = 5000
/** 同源续播写入节流间隔（ms）——交互规格定值 */
const RESUME_THROTTLE_MS = 3000
/** 续播恢复条件：距结尾不足 5s 视为"已看完"，不恢复（交互规格定值） */
const RESUME_TAIL_SECONDS = 5
/** 视为结束的进度阈值（≥95% 清续播 key）——交互规格定值 */
const ENDED_VIDEO_RATIO = 0.95
/** 进度条拖动期间 seek 防抖（ms）——交互规格定值 */
const SEEK_DEBOUNCE_MS = 150
/** 倍速档位（协议无该枚举，客户端固定四档——交互规格定值） */
const PLAYBACK_RATES = [0.5, 1, 1.5, 2] as const
/** localStorage 键：静音状态（'1'=静音 / '0'=未静音；缺失=默认静音） */
const MUTED_STORAGE_KEY = 'qm_video_muted'
/** localStorage 键前缀：同源续播（+ assetId） */
const RESUME_STORAGE_PREFIX = 'qm_resume_'
/** 控制层 hover 态：全屏时也显示（与自动隐藏配合） */
const CONTROLS_LAYER_CLASS = 'absolute inset-x-0 bottom-0 z-10 flex flex-col gap-0.5 bg-gradient-to-t from-black/70 to-transparent px-[var(--qm-space-2)] pt-[var(--qm-space-3)]'

interface VideoPlayerProps {
  detail: AssetDetail
  /** 挂载后自动开始播放（预览态点播放按钮后传入；默认静音保证自动播放策略放行） */
  initAutoPlay?: boolean
}

export function VideoPlayer({ detail, initAutoPlay }: VideoPlayerProps) {
  const assetId = detail.id ?? ''
  const containerRef = useRef<HTMLDivElement | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  // 视频元素以 state 转发给 TimelineBar（ref.current 在渲染期读取不到挂载后的值）
  const [videoEl, setVideoEl] = useState<HTMLVideoElement | null>(null)
  const reportView = useReportView()

  const [isPlaying, setIsPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [durationMs, setDurationMs] = useState(0)
  const [bufferedMs, setBufferedMs] = useState(0)
  const [controlsVisible, setControlsVisible] = useState(true)
  const [muted, setMuted] = useState(() => localStorage.getItem(MUTED_STORAGE_KEY) !== '0')
  const [playbackRate, setPlaybackRate] = useState(1)
  const [isFullscreen, setIsFullscreen] = useState(false)
  // 拖动进度条中的本地值（拖动期间不回跳，松手才 seek）
  const [dragTime, setDragTime] = useState<number | null>(null)

  /** 首次播放上报（幂等：ref 保护，同一次打开只上报一次） */
  const playReportedRef = useRef(false)
  const lastResumeWriteRef = useRef(0)
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const seekTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const resumeKey = `${RESUME_STORAGE_PREFIX}${assetId}`

  /* ---- 挂载即可续播：读取 key 并 seek（非末态才恢复：currentTime < duration-5s） ---- */
  const seekResume = () => {
    const video = videoRef.current
    if (!video || !Number.isFinite(video.duration)) return
    const resume = Number(localStorage.getItem(resumeKey))
    if (Number.isFinite(resume) && resume > 0 && video.duration - resume > RESUME_TAIL_SECONDS) {
      video.currentTime = resume
    }
  }

  /* ---- 播放结束（end 事件）与 ≥95%：清续播 key ---- */
  const clearResume = () => localStorage.removeItem(resumeKey)

  /* ---- 卸载时清续播 key（"退出即清"；99% 用户退出时并未看完，重启从开头看） ---- */
  useEffect(() => {
    return () => {
      localStorage.removeItem(resumeKey)
    }
  }, [resumeKey])

  /* ---- 全屏状态跟踪（Fullscreen API 的事件驱动，按钮/双击/Esc 同步） ---- */
  useEffect(() => {
    const onFullscreenChange = () => setIsFullscreen(document.fullscreenElement === containerRef.current)
    document.addEventListener('fullscreenchange', onFullscreenChange)
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange)
  }, [])

  /* ---- 预览态播放按钮进入：挂载后自动开始（默认静音 → 自动播放策略放行） ---- */
  useEffect(() => {
    if (!initAutoPlay) return
    const video = videoRef.current
    if (video) void video.play()
  }, [initAutoPlay])

  /* ---- 控制栏自动隐藏：5s 无操作；暂停状态不隐藏 ---- */
  const pokeControls = useCallback(() => {
    setControlsVisible(true)
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
    hideTimerRef.current = setTimeout(() => {
      setControlsVisible(false)
    }, CONTROLS_HIDE_DELAY_MS)
  }, [])

  useEffect(() => {
    if (isPlaying) pokeControls()
    else if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
    return () => {
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
    }
  }, [isPlaying, pokeControls])

  /* ---- 播放上报（第一次播放，幂等） ---- */
  const ensurePlayReported = () => {
    if (playReportedRef.current) return
    playReportedRef.current = true
    reportView.mutate({ assetId, kind: 'play' })
  }

  /* ---- timeupdate：续播节流写入 + 结束判定 ---- */
  const handleTimeUpdate = () => {
    const video = videoRef.current
    if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return
    setCurrentTime(video.currentTime)
    const now = Date.now()
    if (now - lastResumeWriteRef.current >= RESUME_THROTTLE_MS) {
      lastResumeWriteRef.current = now
      localStorage.setItem(resumeKey, String(video.currentTime))
    }
    if (video.currentTime / video.duration >= ENDED_VIDEO_RATIO) {
      clearResume()
    }
  }

  const handleLoadedMetadata = () => {
    const video = videoRef.current
    if (!video) return
    setDurationMs(video.duration * 1000)
    seekResume()
  }

  const handleProgress = () => {
    const video = videoRef.current
    if (!video || video.buffered.length === 0) return
    const end = video.buffered.end(video.buffered.length - 1)
    setBufferedMs(Math.min(end, video.duration || end) * 1000)
  }

  /* ---- 进度条 seek（含拖动防抖 150ms） ---- */
  const seekTo = (ratio: number) => {
    const video = videoRef.current
    if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return
    const clamped = Math.min(1, Math.max(0, ratio))
    const target = clamped * video.duration * 1000
    setCurrentTime(target / 1000)
    setDragTime(null)
    video.currentTime = target / 1000
  }

  const scheduleSeek = (ratio: number) => {
    if (seekTimerRef.current) clearTimeout(seekTimerRef.current)
    seekTimerRef.current = setTimeout(() => seekTo(ratio), SEEK_DEBOUNCE_MS)
  }

  const togglePlay = () => {
    const video = videoRef.current
    if (!video) return
    if (video.paused) void video.play()
    else video.pause()
  }

  const toggleMute = () => {
    const next = !muted
    setMuted(next)
    localStorage.setItem(MUTED_STORAGE_KEY, next ? '1' : '0')
    if (videoRef.current) videoRef.current.muted = next
  }

  const toggleFullscreen = () => {
    const node = containerRef.current
    if (!node) return
    if (document.fullscreenElement) {
      void document.exitFullscreen()
    } else {
      void node.requestFullscreen?.()
    }
  }

  /** 进度条上指针位置比例（0~1；click/pointer 事件通用） */
  const ratioOf = (event: { clientX: number; currentTarget: Element }): number => {
    const rect = event.currentTarget.getBoundingClientRect()
    return (event.clientX - rect.left) / rect.width
  }

  /* ---- SeekBar 拖动（pointer capture） ---- */
  const [dragging, setDragging] = useState(false)
  const handleSeekPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    event.preventDefault()
    // 拖动期间保持暂停防卡顿？不暂停——只节流 seek（交互规格：拖动期间 seek 节流 150ms 防抖）
    setDragging(true)
    setDragTime(ratioOf(event) * (durationMs / 1000))
    event.currentTarget.setPointerCapture(event.pointerId)
  }
  const handleSeekPointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!dragging) return
    const ratio = ratioOf(event)
    setDragTime(ratio * (durationMs / 1000))
    scheduleSeek(ratio)
  }
  const handleSeekPointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!dragging) return
    setDragging(false)
    const ratio = ratioOf(event)
    // 防抖计时内可能还有一次 pending，直接结算最终值
    if (seekTimerRef.current) clearTimeout(seekTimerRef.current)
    seekTo(ratio)
  }

  const displayTime = dragTime ?? currentTime
  const progressRatio = durationMs > 0 ? Math.min(1, (displayTime * 1000) / durationMs) : 0

  return (
    <div
      ref={containerRef}
      className="group/player relative size-full bg-black"
      onPointerMove={pokeControls}
      onTouchStart={pokeControls}
      onDoubleClick={toggleFullscreen}
    >
      <video
        ref={(el) => {
          videoRef.current = el
          setVideoEl(el)
        }}
        src={detail.origUrl}
        preload="metadata"
        playsInline
        muted={muted}
        className="size-full object-contain"
        onClick={togglePlay}
        onPlay={() => {
          ensurePlayReported()
          setIsPlaying(true)
        }}
        onPause={() => setIsPlaying(false)}
        onTimeUpdate={handleTimeUpdate}
        onLoadedMetadata={handleLoadedMetadata}
        onProgress={handleProgress}
        onEnded={clearResume}
      />

      {/* 控制布局：暂停时强制可见（isPlaying false → visible） */}
      <div
        className={CONTROLS_LAYER_CLASS}
        style={{ opacity: controlsVisible || !isPlaying ? 1 : 0, transition: 'opacity 200ms ease' }}
      >
        {/* 时间轴标签条（进度条上方） */}
        <TimelineBar assetId={assetId} video={videoEl} />

        {/* 自绘进度条：点击 seek + 拖拽（含已缓冲显示；seek 在 up 时结算） */}
        <div
          className="relative h-4 cursor-pointer touch-none"
          onPointerDown={handleSeekPointerDown}
          onPointerMove={handleSeekPointerMove}
          onPointerUp={handleSeekPointerUp}
        >
          <div className="absolute inset-x-0 top-1/2 h-1 -translate-y-1/2 overflow-hidden rounded-full bg-white/20">
            <div className="absolute inset-y-0 left-0 bg-white/40" style={{ width: `${durationMs > 0 ? (bufferedMs / durationMs) * 100 : 0}%` }} />
            <div className="absolute inset-y-0 left-0 bg-white" style={{ width: `${progressRatio * 100}%` }} />
          </div>
          <div
            className="absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white"
            style={{ left: `${progressRatio * 100}%` }}
          />
        </div>

        {/* 控制按钮行 */}
        <div className="flex items-center gap-1 text-white">
          <Button size="icon-sm" variant="ghost" onClick={togglePlay} aria-label={isPlaying ? '暂停' : '播放'} className="text-white hover:bg-white/20">
            {isPlaying ? <Pause className="size-4" aria-hidden /> : <Play className="size-4" aria-hidden />}
          </Button>
          <span className="text-xs tabular-nums">{formatDuration(displayTime * 1000)} / {formatDuration(durationMs)}</span>

          <div className="flex-1" />

          {/* 倍速选择 */}
          <Popover>
            <PopoverTrigger asChild>
              <Button size="sm" variant="ghost" className="text-white hover:bg-white/20" aria-label="播放速度">
                {playbackRate}x
              </Button>
            </PopoverTrigger>
            <PopoverContent align="end" className="w-24 p-1">
              {PLAYBACK_RATES.map((rate) => (
                <Button
                  key={rate}
                  size="sm"
                  variant={playbackRate === rate ? 'default' : 'ghost'}
                  className="w-full justify-center"
                  onClick={() => {
                    setPlaybackRate(rate)
                    if (videoRef.current) videoRef.current.playbackRate = rate
                  }}
                >
                  {rate}x
                </Button>
              ))}
            </PopoverContent>
          </Popover>

          <Button size="icon-sm" variant="ghost" onClick={toggleMute} aria-label={muted ? '取消静音' : '静音'} className="text-white hover:bg-white/20">
            {muted ? <VolumeX className="size-4" aria-hidden /> : <Volume2 className="size-4" aria-hidden />}
          </Button>

          <Button size="icon-sm" variant="ghost" onClick={toggleFullscreen} aria-label={isFullscreen ? '退出全屏' : '全屏'} className="text-white hover:bg-white/20">
            {isFullscreen ? <Minimize className="size-4" aria-hidden /> : <Maximize className="size-4" aria-hidden />}
          </Button>
        </div>
      </div>
    </div>
  )
}
