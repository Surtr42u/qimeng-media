/**
 * 行为打点口径纯函数（2026-09-11 打点口径批，口径 B；DOMAIN_RULES §5）。
 *
 * lib 纯函数层（ADR-0017）：无 IO、无 React——打点判定是口径载体，行为由单测
 * 锁定（与推荐算法/统计聚合同族：口径进纯函数，hook/组件层只做接线，铁律 7）。
 * 接线方：hooks/use-dwell-report.ts（dwell 决策）、components/media/video-player.tsx
 * （play 防重状态机）。
 */

/** dwell 段上报决策（decideDwellSegment 返回值） */
export interface DwellSegmentDecision {
  /**
   * false = 该段不上报。仅对非正值/非有限毫秒数（0ms、负值、脏数据）防御——
   * 口径 B 起不足 1s 的合法停留段照报（seconds 四舍五入后为 0 的零值行合法，
   * 原「<1s 段不上报」口径 A 就此退役）。
   */
  report: boolean
  /** 上报秒数 = 毫秒数 ÷1000 四舍五入；report=false 时为 0 */
  seconds: number
}

/**
 * dwell 段是否上报 + 秒数取整的单点决策。
 * 口径（DOMAIN_RULES §5「浏览时长：详情页停留秒数」）：一次连续停留恰好一条
 * dwell 事件，段时长不足 1s 同样上报（seconds 可为 0）；仅非正值/非有限值
 * （时钟异常/脏数据）不上报。
 */
export function decideDwellSegment(elapsedMs: number): DwellSegmentDecision {
  if (!Number.isFinite(elapsedMs) || elapsedMs <= 0) return { report: false, seconds: 0 }
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
