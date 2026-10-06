/**
 * ArtPlayer 视频播放器封装（W-3；官方文档 https://artplayer.org/document，v5.4.0）。
 *
 * 职责边界：纯 UI 组件——不发任何网络请求（铁律 7）；断点续播起点与进度上报由
 * AssetDetailPage 经 hooks（use-progress.ts）以 props 注入。
 * 生命周期：实例在 effect 内创建、清理函数 destroy（官方卸载语义）；回调经 ref
 * 转发，props 刷新不重建播放器实例。
 *
 * 原生内核接管（C3，桌面壳内专用）：`__TAURI__` 全局存在时渲染「原生内核」chip，
 * 点击 invoke mpv_open 在壳侧开 libmpv 独立播放窗；期间 web 播放器暂停、每
 * PROGRESS_REPORT_INTERVAL_MS 轮询 mpv_status，边沿信号喂同一口径 B 闸门
 * （lib/engagement-reporting nativePollSignal），进度/起播/暂停上报链与 web 路径
 * 完全同构。IPC 协议（命令名/参数名/控制动作字面量）与 desktop/src-tauri
 * mpv/commands.rs 双写，改名须两端同步。Tauri v2 withGlobalTauri 官方文档：
 * https://v2.tauri.app/develop/calling-rust/（invoke 走 window.__TAURI__.core.invoke，
 * 参数键 camelCase：Rust start_secs ↔ JS startSecs）。
 */

import Artplayer from 'artplayer'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  nativePollSignal,
  stepPlayGate,
  type NativePollStatus,
  type PlayGateState,
} from '@/lib/engagement-reporting'
import { PROGRESS_REPORT_INTERVAL_MS } from '@/lib/progress-report'

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
  /** 起播（页面侧上报 play 事件）。口径 B（2026-09-11，DOMAIN_RULES §5）：同一
   * 连续播放段双来源（art 'play' 主路径 ∪ 'video:play' 原生兜底）只派发一条
   * （组件内防重闸门），暂停后重新起播重新派发——「每次起播一条」语义不变，
   * 同会话当日重复起播的去重仍是服务端职责。 */
  onPlay?: () => void
  /** 原生内核播放窗标题（页面侧按 cosWork ?? fileName 口径注入；仅桌面壳内消费） */
  nativeTitle?: string
}

/** Tauri v2 withGlobalTauri 注入的最小类型面（仅桌面壳内存在；浏览器无此全局） */
interface TauriIpc {
  core: {
    invoke: (cmd: string, args?: Record<string, unknown>) => Promise<unknown>
  }
}

/**
 * 读壳 IPC 全局：titlebar.js 同款惰性防御——壳注入时机可能晚于页面脚本，
 * 不在模块加载/渲染期取用，点击与轮询发生时再读（届时注入必已完成）。
 */
function tauriIpc(): TauriIpc | null {
  const t = (window as unknown as { __TAURI__?: Partial<TauriIpc> }).__TAURI__
  return t?.core?.invoke ? (t as TauriIpc) : null
}

/** 桌面壳判定（渲染期读：React 挂载晚于壳 IPC 注入；浏览器环境恒 false 不渲染 chip） */
function inTauriShell(): boolean {
  return typeof window !== 'undefined' && '__TAURI__' in window
}

/** 运行时读主题 token（§5.8 口径：色值禁止进 JS/配置，只认 --qm-primary） */
function readPrimaryColor(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--qm-primary').trim()
}

/**
 * 进度条点击/拖拽 seek 归一：项目全局 `html { zoom: 1.1 }`（prototype.css，
 * 原型视口口径）下，ArtPlayer 官方坐标换算（getPosFromEvent：event.clientX
 * 视觉 px ÷ $progress.clientWidth 布局 px 混算）会把 seek 目标系统性放大
 * ×zoom——点击实测点 45s 落 49.5s，拖拽同理（官方 mousedown 只置内部拖拽
 * 标志、document mousemove 才按混算坐标 seek，已核对 5.4.0 打包源码）。
 *
 * 官方换算函数是模块内部实现无法补丁，故在事件层接管：捕获阶段拦下进度条
 * 的 click/mousedown（官方 biased 处理不再执行），改用纯视觉坐标（clientX
 * 与 getBoundingClientRect 同参照系）自行求目标时刻走官方 seek 写入；拖拽
 * 期间在 document 层按自家标志补 mousemove seek（官方标志被拦永不起臂）。
 * 悬停时间预览走 progress 元素自己的 mousemove，不受拦截影响。
 */
function attachProgressSeekFix(container: HTMLElement, art: Artplayer): () => void {
  let dragging = false

  /** 视觉坐标 → seek（clamp 到 0..duration，拖到进度条外按端点处理，同官方语义） */
  const seekToClientX = (clientX: number): void => {
    const $progress = container.querySelector<HTMLElement>('.art-control-progress')
    if (!$progress) return
    const rect = $progress.getBoundingClientRect()
    const duration = art.duration
    if (rect.width <= 0 || !Number.isFinite(duration) || duration <= 0) return
    const fraction = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
    art.seek = fraction * duration
  }

  const inProgress = (target: EventTarget | null): target is Element =>
    target instanceof Element && target.closest('.art-control-progress') !== null

  const onClickCapture = (event: MouseEvent) => {
    if (!inProgress(event.target)) return
    if ((event.target as Element).closest('.art-progress-indicator')) return // 与官方一致：指示器点击不 seek
    seekToClientX(event.clientX)
    event.stopPropagation() // 官方缩放偏置版 handler 不再执行
  }

  // 拖拽臂：左键按下进度条（含指示器——它是拖拽手柄）改挂自家标志，官方标志不再启动
  const onMouseDownCapture = (event: MouseEvent) => {
    if (event.button !== 0 || !inProgress(event.target)) return
    dragging = true
    event.stopPropagation()
  }
  const onDocumentMouseMove = (event: MouseEvent) => {
    if (dragging) seekToClientX(event.clientX)
  }
  const onDocumentMouseUp = () => {
    dragging = false
  }

  container.addEventListener('click', onClickCapture, true)
  container.addEventListener('mousedown', onMouseDownCapture, true)
  document.addEventListener('mousemove', onDocumentMouseMove)
  document.addEventListener('mouseup', onDocumentMouseUp)
  return () => {
    container.removeEventListener('click', onClickCapture, true)
    container.removeEventListener('mousedown', onMouseDownCapture, true)
    document.removeEventListener('mousemove', onDocumentMouseMove)
    document.removeEventListener('mouseup', onDocumentMouseUp)
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
  nativeTitle,
}: VideoPlayerProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  // ArtPlayer 实例引用：原生内核起播要读 web 当前进度（优先于断点起点）并暂停 web 播放
  const artRef = useRef<Artplayer | null>(null)

  // —— 原生内核接管态（仅桌面壳内有意义）——
  const isTauriShell = inTauriShell()
  const [nativeActive, setNativeActive] = useState(false)
  const [nativeError, setNativeError] = useState<string | null>(null)
  // 上一轮轮询快照（nativePollSignal 的 prev 输入）；null=尚无会话状态
  const nativeStatusRef = useRef<NativePollStatus | null>(null)
  // 接管态的同步镜像：ArtPlayer play 事件回调（effect 闭包）与轮询需同步读取，
  // 收回动作必须即时翻转、不等下一次 render（轮询 effect 卸载与事件回调竞态防御）
  const nativeActiveRef = useRef(false)
  // 收回回调经 ref 进 effect 闭包（实例建后 props 重建不触发，latest-ref 模式见下）
  const closeNativeRef = useRef<() => void>(() => {})

  useEffect(() => {
    nativeActiveRef.current = nativeActive
  }, [nativeActive])

  // 创建参数挂载时定格：断点续播起点/签名直链/时间轴打点只认首挂值——父级进度
  // 上报或查询失效触发的 refetch 改变 props 不重建播放器（换资产由页面侧 key
  // 重建保证）。打点官方 API 仅在构造/loadedmetadata 时消费、挂载后不动态更新，
  // 页面以「tags isPending || isPlaceholderData 不挂载」守卫保证首挂即全量、
  // 且绝非上一资产的旧打点，定格即完整（F2·P1-1：keepPreviousData 占位期 v5
  // 乐观 status='success'，isLoading 拦不住占位，契约见 hooks/use-progress.ts）。
  // 播放回调是唯一例外，走下方 ref 转发保持最新（lint 合规：ref 同步放 effect，
  // 且声明在建实例 effect 之前，首挂时先于建实例执行）。
  const [initial] = useState({ src, poster, startTime, highlights })
  const handlersRef = useRef({ onTimeUpdate, onPause, onPlay })
  useEffect(() => {
    handlersRef.current = { onTimeUpdate, onPause, onPlay }
  })
  // play 防重闸门状态（口径 B：同一起播双来源只派发一条，判定纯函数在
  // lib/engagement-reporting；建实例 effect 内随新实例重置——新实例=新会话）
  const playGateRef = useRef<PlayGateState>('idle')

  /**
   * 收回原生内核：关会话 + 复位接管态。闸门直接归零（新会话=新起播段，与
   * nativePollSignal「关闭不产出信号」的口径配对）。invoke 尽力而为：壳退出中
   * 调用失败无需处理；会话已被用户关窗时 Rust 侧 mpv_close 幂等返回 Ok。
   */
  const closeNative = useCallback(() => {
    nativeActiveRef.current = false
    setNativeActive(false)
    nativeStatusRef.current = null
    playGateRef.current = 'idle'
    tauriIpc()
      ?.core.invoke('mpv_close')
      .catch(() => {})
  }, [])
  useEffect(() => {
    closeNativeRef.current = closeNative
  }, [closeNative])

  /** 原生内核起播：web 当前进度优先、断点起点兜底；成功后暂停 web 播放器
   * （其 pause 事件路径会把 web 最后位置照常补报，口径不变）。DLL 缺失等错误
   * 串自带 setup 指引（commands.rs），原样展示在 chip 旁。 */
  const openNative = async () => {
    const t = tauriIpc()
    if (!t || nativeActiveRef.current) return
    const webPosition = artRef.current?.currentTime ?? 0
    const startSeconds = webPosition > 0 ? webPosition : initial.startTime
    try {
      await t.core.invoke('mpv_open', {
        url: initial.src,
        title: nativeTitle ?? '绮梦播放窗', // 页面未传标题时的窗标题兜底（仅壳内可见）
        startSecs: startSeconds,
      })
      setNativeError(null)
      nativeStatusRef.current = null // 新会话：轮询边沿从零起算（首拍非空快照=play）
      playGateRef.current = 'idle'
      nativeActiveRef.current = true
      setNativeActive(true)
      artRef.current?.pause()
    } catch (e) {
      setNativeError(e instanceof Error ? e.message : String(e))
    }
  }

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    // 倍速档位表是静态属性（构造时读取），必须先覆盖再实例化
    Artplayer.PLAYBACK_RATE = PLAYBACK_RATES
    playGateRef.current = 'idle' // 新播放器实例 = 新播放会话，闸门归零
    // 桌面壳检测（Tauri v2 withGlobalTauri 注入 window.__TAURI__）：壳内禁用系统全屏
    const isTauriShell = '__TAURI__' in window
    const art = new Artplayer({
      container,
      url: initial.src,
      poster: initial.poster,
      theme: readPrimaryColor(), // 进度条/打点主题色 = 主色 token
      lang: 'zh-cn',
      setting: true, // 设置面板开关（倍速入口依赖）
      playbackRate: true,
      // 系统全屏在桌面壳内禁用（2026-09-17 实测卡死+研究定案）：壳层「HTML5 全屏
      // 联动宿主窗口」在退出全屏过渡期同步回打 WebView2 互等死锁（tauri#11254 同型、
      // 未真修）；桌面内只留网页全屏（铺满窗口），根治（壳层窗口 API 接管）留跟进批
      fullscreen: !isTauriShell,
      // 网页全屏（铺满页面=桌面客户端里「占满窗口」，与系统全屏并存两种）：
      // 官方 option（artplayer.org/document option#fullscreenweb），控制栏独立按钮
      fullscreenWeb: true,
      highlight: initial.highlights,
      // 默认静音（2026-09-17 用户拍板）：打开视频初始 muted，点控制栏音量/取消
      // 静音后正常发声（官方 option#muted，types/option.d.ts）
      muted: true,
    })

    // 倍速菜单文案：官方 selector 标签生成用 toFixed(1)，0.75/1.25 显示成
    // 「0.8/1.3」（§5 第 12 条②）。按内建条目名 'playback-rate' 定位做
    // setting.update——面板 update 按 name 精确匹配、命中才 merge 重渲染，
    // 未命中会把传入项当新条目 add 成无 click 的死行（'playbackRate' 是
    // 右键菜单条目名，设置面板里不存在，此前用它属无效修复+死行来源）。
    // 这里只换各档位显示文案为精确值；value/item.name/onSelect（官方选档、
    // 勾选高亮与 tooltip 回填）结构原样保留，选档行为不受文案影响。
    art.setting.update({
      name: 'playback-rate',
      selector: PLAYBACK_RATES.map((rate) => ({
        value: rate,
        name: `playback-rate-${rate}`,
        default: rate === art.playbackRate,
        html: rate === 1 ? art.i18n.get('Normal') : `${rate}x`,
      })),
    })

    // 断点续播：元数据就绪后跳到起点（ready = 官方「首次可播」事件）
    if (initial.startTime > 0) art.on('ready', () => (art.seek = initial.startTime))
    art.on('video:timeupdate', () => handlersRef.current.onTimeUpdate?.(art.currentTime))
    art.on('pause', () => {
      playGateRef.current = stepPlayGate(playGateRef.current, 'pause').state
      handlersRef.current.onPause?.(art.currentTime)
    })
    // play 双来源单发（口径 B，替换第三十八笔口径 A 的「video:play 不混用」取舍）：
    // art 'play' 主路径保留，旁补 'video:play' 原生兜底（覆盖自动播放恢复等非
    // art.play() 起播路径）；两路喂同一防重状态机，同一起播只派发一条 onPlay。
    // 暂停重置走双源（'video:pause' 原生兜底 + 上方 art 'pause'），任一来源的
    // 暂停都把闸门拨回 idle，暂停后重新起播能重新派发。防重判定逻辑在
    // lib/engagement-reporting 纯函数（铁律 7），此处仅接线。
    const handlePlaySignal = () => {
      // 原生接手中用户点 web 播放 = 收回原生（关会话+复位），web 路径照常起播；
      // 必须在闸门步进前执行：收回把闸门归零，本次起播能重新派发 play 上报
      if (nativeActiveRef.current) closeNativeRef.current()
      const step = stepPlayGate(playGateRef.current, 'play')
      playGateRef.current = step.state
      if (step.report) handlersRef.current.onPlay?.()
    }
    const handlePauseReset = () => {
      playGateRef.current = stepPlayGate(playGateRef.current, 'pause').state
    }
    art.on('play', handlePlaySignal)
    art.on('video:play', handlePlaySignal)
    art.on('video:pause', handlePauseReset)

    // 深色模式跟随 token：月亮按钮切 html class 时重读 --qm-primary
    const themeObserver = new MutationObserver(() => {
      art.theme = readPrimaryColor()
    })
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })

    const detachSeekFix = attachProgressSeekFix(container, art)
    artRef.current = art

    return () => {
      themeObserver.disconnect()
      detachSeekFix()
      artRef.current = null
      art.destroy() // 官方实例销毁（默认连带移除挂载 DOM）
    }
    // initial.* 挂载时定格（useState 惰性初始化），属性值恒不变化，列入 deps 仅满足规则
  }, [initial.src, initial.poster, initial.startTime, initial.highlights])

  // 原生接手中的状态轮询：每 PROGRESS_REPORT_INTERVAL_MS invoke('mpv_status')
  // （与 web 心跳节流同拍），边沿信号喂同一口径 B 闸门。播中每轮 tick 喂
  // onTimeUpdate（hook 内 5s 节流同拍，不会超发）；暂停/eof 边沿带轮询快照位置
  // 走 onPause（flush 补报）。next=null 且 prev 非空 = 会话已结束（用户关窗/
  // 壳侧回收）→ 复位接管态让 web 控制条与 chip 回归，不额外补报（最后 tick
  // 位置已在 useProgress 的 lastRef 里，离开页面时照常卸载补报）。
  useEffect(() => {
    if (!nativeActive) return
    let stopped = false
    const poll = async () => {
      const t = tauriIpc()
      if (!t || stopped) return
      try {
        // Rust MpvStatus serde 序列化键（position/duration/paused/eof）即此形态
        const next = (await t.core.invoke('mpv_status')) as NativePollStatus | null
        if (stopped) return
        const prev = nativeStatusRef.current
        nativeStatusRef.current = next
        const signal = nativePollSignal(prev, next)
        if (signal) {
          const step = stepPlayGate(playGateRef.current, signal)
          playGateRef.current = step.state
          if (signal === 'play') {
            if (step.report) handlersRef.current.onPlay?.()
          } else if (next) {
            handlersRef.current.onPause?.(next.position)
          }
        }
        if (next && !next.paused && !next.eof) {
          handlersRef.current.onTimeUpdate?.(next.position)
        }
        if (next === null && prev !== null) {
          nativeActiveRef.current = false
          playGateRef.current = 'idle'
          setNativeActive(false)
        }
      } catch {
        // 壳 IPC 异常（壳退出中等）：忽略本轮；会话真死时下一轮 mpv_status
        // 会正常返回 null 走复位路径，无需因单次异常拆会话
      }
    }
    void poll() // 开轮即查一次：起播上报与首个 tick 不等第一个 5s 拍
    const timer = window.setInterval(() => void poll(), PROGRESS_REPORT_INTERVAL_MS)
    return () => {
      stopped = true
      window.clearInterval(timer)
    }
  }, [nativeActive])

  // 组件卸载时原生会话一并回收（验收 2「关 web 页 → 会话回收干净」）：
  // 不等用户手关播放窗；此刻最后一次轮询 tick 位置已在 useProgress lastRef，
  // 其卸载补报 effect 会照常上报（原生/两内核路径同构）
  useEffect(
    () => () => {
      if (nativeActiveRef.current) closeNativeRef.current()
    },
    [],
  )

  // 壳内：外层 wrap 承载 chip（ArtPlayer 独占 .video-player-box 容器内容，chip
  // 严禁塞进挂载容器内部）；chip 绝对定位悬浮右上，不参与舞台布局、不遮控制条。
  // 浏览器模式：维持迁移前形态（单容器，零改动）。
  if (!isTauriShell) return <div className="video-player-box" ref={containerRef} />

  return (
    <div className="video-player-wrap">
      <div className="video-player-box" ref={containerRef} />
      {initial.src ? (
        <div className="mpv-chip-row">
          <button
            type="button"
            className={`mpv-chip${nativeActive ? ' active' : ''}`}
            onClick={() => {
              if (nativeActive) closeNative()
              else void openNative()
            }}
          >
            {nativeActive ? '原生内核播放中 · 返回 Web 内核' : '原生内核播放'}
          </button>
          {nativeError ? <span className="mpv-chip-error">{nativeError}</span> : null}
        </div>
      ) : null}
    </div>
  )
}
