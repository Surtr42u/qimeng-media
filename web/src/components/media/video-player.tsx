/**
 * ArtPlayer 视频播放器封装（W-3；官方文档 https://artplayer.org/document，v5.4.0）。
 *
 * 职责边界：纯 UI 组件——不发任何网络请求（铁律 7）；断点续播起点与进度上报由
 * AssetDetailPage 经 hooks（use-progress.ts）以 props 注入。
 * 生命周期：实例在 effect 内创建、清理函数 destroy（官方卸载语义）；回调经 ref
 * 转发，props 刷新不重建播放器实例。
 */

import Artplayer from 'artplayer'
import { useEffect, useRef, useState } from 'react'

/** 倍速菜单档位 0.5~3x（W-3 冻结清单；官方默认最高 2x，构造前覆盖静态档位表生效） */
const PLAYBACK_RATES = [0.5, 0.75, 1, 1.25, 1.5, 2, 3]

/** 进度条打点（时间轴标签 timeMillis/1000 换算成秒后的播放器形态） */
export interface PlayerHighlight {
  /** 时间点（秒） */
  time: number
  /** 悬停提示文本（标签名） */
  text: string
}

export interface VideoPlayerProps {
  /** 视频签名直链（detail.origUrl） */
  src: string
  poster?: string
  /** 断点续播起点（秒；已看完场景由页面推导传 0） */
  startTime?: number
  /** 时间轴标签打点（空数组=无标签，官方 highlight 层不渲染任何点） */
  highlights?: PlayerHighlight[]
  /** 播放中心跳（页面侧经 useProgress 5s 节流上报） */
  onTimeUpdate?: (positionSeconds: number) => void
  /** 暂停（页面侧立即上报） */
  onPause?: (positionSeconds: number) => void
}

/** 运行时读主题 token（§5.8 口径：色值禁止进 JS/配置，只认 --qm-primary） */
function readPrimaryColor(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--qm-primary').trim()
}

/**
 * 进度条点击 seek 归一：项目全局 `html { zoom: 1.1 }`（prototype.css，原型视口
 * 口径）下，ArtPlayer getPosFromEvent 以视觉 px（event.clientX）÷ 布局 px
 * （$progress.clientWidth）混算，seek 目标会系统性放大 ×zoom（实测点 45s 打点
 * 落在 49.5s，真实用户同样命中）。捕获阶段改用纯视觉坐标（clientX 与
 * getBoundingClientRect 同参照系）自行求目标时刻，再走官方 seek 写入。
 */
function attachProgressSeekFix(container: HTMLElement, art: Artplayer): () => void {
  const onClickCapture = (event: MouseEvent) => {
    const target = event.target
    if (!(target instanceof Element)) return
    if (!target.closest('.art-control-progress')) return // 只接管进度条点击，其余控件照常
    if (target.closest('.art-progress-indicator')) return // 与官方一致：指示器点击不 seek
    const $progress = container.querySelector<HTMLElement>('.art-control-progress')
    if (!$progress) return
    const rect = $progress.getBoundingClientRect()
    const duration = art.duration
    if (rect.width <= 0 || !Number.isFinite(duration) || duration <= 0) return
    const fraction = (event.clientX - rect.left) / rect.width
    if (fraction < 0 || fraction > 1) return
    art.seek = fraction * duration
    event.stopPropagation() // 官方缩放偏置版 handler 不再执行
  }
  container.addEventListener('click', onClickCapture, true)
  return () => container.removeEventListener('click', onClickCapture, true)
}

export default function VideoPlayer({
  src,
  poster,
  startTime = 0,
  highlights = [],
  onTimeUpdate,
  onPause,
}: VideoPlayerProps) {
  const containerRef = useRef<HTMLDivElement>(null)

  // 创建参数挂载时定格：断点续播起点/签名直链/时间轴打点只认首挂值——父级进度
  // 上报或查询失效触发的 refetch 改变 props 不重建播放器（换资产由页面侧 key
  // 重建保证）。打点官方 API 仅在构造/loadedmetadata 时消费、挂载后不动态更新，
  // 页面以 tagsLoading 守卫保证首挂即全量标签，定格即完整。播放回调是唯一
  // 例外，走下方 ref 转发保持最新（lint 合规：ref 同步放 effect，且声明在建
  // 实例 effect 之前，首挂时先于建实例执行）。
  const [initial] = useState({ src, poster, startTime, highlights })
  const handlersRef = useRef({ onTimeUpdate, onPause })
  useEffect(() => {
    handlersRef.current = { onTimeUpdate, onPause }
  })

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    // 倍速档位表是静态属性（构造时读取），必须先覆盖再实例化
    Artplayer.PLAYBACK_RATE = PLAYBACK_RATES
    const art = new Artplayer({
      container,
      url: initial.src,
      poster: initial.poster,
      theme: readPrimaryColor(), // 进度条/打点主题色 = 主色 token
      lang: 'zh-cn',
      setting: true, // 设置面板开关（倍速入口依赖）
      playbackRate: true,
      fullscreen: true,
      highlight: initial.highlights,
    })

    // 断点续播：元数据就绪后跳到起点（ready = 官方「首次可播」事件）
    if (initial.startTime > 0) art.on('ready', () => (art.seek = initial.startTime))
    art.on('video:timeupdate', () => handlersRef.current.onTimeUpdate?.(art.currentTime))
    art.on('pause', () => handlersRef.current.onPause?.(art.currentTime))

    // 深色模式跟随 token：月亮按钮切 html class 时重读 --qm-primary
    const themeObserver = new MutationObserver(() => {
      art.theme = readPrimaryColor()
    })
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })

    const detachSeekFix = attachProgressSeekFix(container, art)

    return () => {
      themeObserver.disconnect()
      detachSeekFix()
      art.destroy() // 官方实例销毁（默认连带移除挂载 DOM）
    }
    // initial.* 挂载时定格（useState 惰性初始化），属性值恒不变化，列入 deps 仅满足规则
  }, [initial.src, initial.poster, initial.startTime, initial.highlights])

  return <div className="video-player-box" ref={containerRef} />
}
