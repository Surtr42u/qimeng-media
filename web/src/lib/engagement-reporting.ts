/**
 * 行为打点口径纯函数（DOMAIN_RULES §5）。
 *
 * lib 纯函数层（ADR-0017）：无 IO、无 React——打点判定是口径载体，行为由单测
 * 锁定（与推荐算法/统计聚合同族：口径进纯函数，hook/组件层只做接线，铁律 7）。
 * 接线方：hooks/use-dwell-report.ts（dwell 决策）、components/media/video-player.tsx
 * （play 防重状态机）。
 */

/**
 * 停留段最小上报时长（毫秒）：不足 1s 的停留段秒数四舍五入后为 0，累加
 * 无意义且会给事件流制造零值噪声（快速划过），不产生事件；浏览 open 事件
 * 已计入访问（W6 #45 用户拍板回退，DOMAIN_RULES §5），数值口径不受影响
 * （少计的 <1s 在任意统计窗口内都不足 1 秒）。
 */
const MIN_REPORT_SEGMENT_MS = 1000

/** dwell 段上报决策（decideDwellSegment 返回值） */
export interface DwellSegmentDecision {
  /**
   * false = 该段不上报。不足 1s 的停留段不上报（2026-09-12 W6 #45 回退：
   * 恢复原「<1s 段不上报」口径，V5 口径 B 废止）；另对非有限毫秒数
   * （NaN/Infinity，时钟异常/脏数据）防御。
   */
  report: boolean
  /** 上报秒数 = 毫秒数 ÷1000 四舍五入；report=false 时为 0 */
  seconds: number
}

/**
 * dwell 段是否上报 + 秒数取整的单点决策。
 * 口径（DOMAIN_RULES §5「浏览时长：详情页停留秒数」）：一次连续停留恰好一条
 * dwell 事件；段时长不足 1s（MIN_REPORT_SEGMENT_MS）不上报，仅非有限值
 * （时钟异常/脏数据）一并防御拦截。
 */
export function decideDwellSegment(elapsedMs: number): DwellSegmentDecision {
  if (!Number.isFinite(elapsedMs) || elapsedMs < MIN_REPORT_SEGMENT_MS) {
    return { report: false, seconds: 0 }
  }
  return { report: true, seconds: Math.round(elapsedMs / 1000) }
}

/**
 * play 防重状态机状态：
 * - idle：当前连续播放段尚未派发过 play 上报（含初始态/暂停后）；
 * - reported：本段已派发过，同段后续起播信号跳过。
 */
export type PlayGateState = 'idle' | 'reported'

/**
 * play 防重状态机事件。来源在接线层归一（口径 B：play 事件覆盖面 =
 * 播放器起播路径 art 'play' ∪ 原生 'video:play' 兜底，两路喂同一信号；
 * pause 同理 = art 'pause' ∪ 'video:pause'），状态机不区分来源。
 */
export type PlayGateEvent = 'play' | 'pause'

/** 单步迁移结果：report=true 表示该起播信号需要发 play 上报 */
export interface PlayGateStep {
  report: boolean
  state: PlayGateState
}

/**
 * play 双来源单发状态机（DOMAIN_RULES §5「同一次起播客户端防重只报一条」）：
 * - 任一来源 play：idle → 派发并进入 reported；reported → 跳过（防双源双发）；
 * - 任一来源 pause：回到 idle（重置——暂停后重新起播属新起播，重新派发；
 *   同会话当日的重复起播去重仍是服务端职责，客户端只防「同一次起播双发」）。
 */
export function stepPlayGate(state: PlayGateState, event: PlayGateEvent): PlayGateStep {
  if (event === 'pause') return { report: false, state: 'idle' }
  return state === 'idle' ? { report: true, state: 'reported' } : { report: false, state: 'reported' }
}

/** 一条字幕轨（壳侧 MpvStatus.media.tracks[i] 同构；serde 默认键名=snake_case） */
export interface NativeTrackInfo {
  /** mpv 轨道 id（写回 sid 用它） */
  id: number
  title: string
  lang: string
  external: boolean
}

/**
 * 媒体信息探测（壳侧半秒一拍，随 mpv_status 一起回来）。
 * 字段名与壳侧 MediaProbe 的 serde 序列化键一致（双写须同步）。
 */
export interface NativeMediaProbe {
  /** 视频宽（未知=0） */
  video_width: number
  /** 视频高（未知=0）——清晰度文案以**高度**分档 */
  video_height: number
  /** 视频编码（h264/hevc…） */
  video_codec: string
  /** 容器帧率（未知=0） */
  fps: number
  /** 画面比例覆盖值（"no"=默认） */
  aspect: string
  /** 单片循环 */
  loop_file: boolean
  /** 当前字幕轨 id（0=关闭） */
  sid: number
  tracks: NativeTrackInfo[]
}

/**
 * 原生内核（libmpv，桌面壳内）轮询快照：每 250ms invoke('mpv_status') 的返回形态。
 * 字段名与壳侧 MpvStatus 的 serde 序列化键一致（desktop/src-tauri mpv/player.rs
 * 双写，改名须两端同步）；null = 无活动播放（窗口已关/会话已死/尚未起播）。
 * speed/volume/muted/media 为自绘控制条用（旧壳后端无这些字段 → undefined，
 * 调用点已兜底）。
 */
export interface NativePollStatus {
  /** 当前播放位置（秒） */
  position: number
  /** 总时长（秒，未知=0） */
  duration: number
  /** 是否暂停 */
  paused: boolean
  /** 是否播完（keep-open 播完停帧不清屏） */
  eof: boolean
  /** 倍速（mpv speed） */
  speed?: number
  /** 音量（mpv volume 0~130） */
  volume?: number
  /** 是否静音 */
  muted?: boolean
  /** 媒体信息（清晰度/字幕/设置三入口的数据源） */
  media?: NativeMediaProbe
}

/** 轮询边沿产出的口径 B 状态机事件（与 ArtPlayer 双来源信号同一闸门） */
export type NativePollSignal = 'play' | 'pause'

/**
 * 轮询边沿 → play/pause 信号（喂 stepPlayGate 同一口径 B 状态机）：
 * 相邻两次 mpv_status 快照对比，把状态变化翻译成与 ArtPlayer 同源的闸门事件。
 * - 首个非空快照（prev=null）：未暂停且未播完 = 会话起播（'play'）；开局即
 *   暂停/eof 则等恢复边沿再报（起播上报语义=真实起播，非会话建立）；
 * - paused 边沿 false→true / true→false → 'pause' / 'play'（用户经 mpv OSC
 *   或壳内快捷键暂停/恢复）；
 * - eof false→true → 'pause'（keep-open 播完停帧；HTML5 video 自然播完同样
 *   触发 pause 事件，两内核口径对齐：播完=最后位置补报 + 闸门复位）；
 * - 会话关闭（next=null）不产出信号：闸门复位属接线层职责（直接归零，
 *   新会话=新起播段），不借道 pause 事件；
 * - 快照无变化 → null（同一状态内不重复派发）。
 */
export function nativePollSignal(
  prev: NativePollStatus | null,
  next: NativePollStatus | null,
): NativePollSignal | null {
  if (!next) return null
  if (!prev) return next.paused || next.eof ? null : 'play'
  if (next.eof && !prev.eof) return 'pause'
  if (!prev.paused && next.paused) return 'pause'
  if (prev.paused && !next.paused) return 'play'
  return null
}
