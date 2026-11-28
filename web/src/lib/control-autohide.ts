/**
 * 控制条自动隐藏调度器（纯逻辑，便于单测）。
 *
 * 背景：项目所用 ArtPlayer 5.4 的 `option.d.ts` 已没有 `autoHide` / `autoHideTimeout`
 * 字段，dist 里也不存在该字符串——内置的「静止 N 秒自动收起」在 v5 已被移除，
 * 控制条支持显式 `controls.show = true/false`，但没有任何空闲计时。
 * 所以由播放器层统一调度：鼠标/触屏活动 → 显示并重新计时；计时到点且没有面板
 * 打开时才真正收起。把状态机抽成这个工厂，避免再出现「某个标志残留 → 永不收起」。
 */
export interface AutoHideDeps {
  /** 空闲多少毫秒后收起 */
  hideMs: number
  /** 由调用方注入，便于测试替换 */
  setTimer: (fn: () => void, ms: number) => number
  clearTimer: (id: number) => void
  /** 真正执行收起 */
  onHide: () => void
  /** 此刻是否应该阻止收起（菜单/设置面板打开） */
  isBlocked: () => boolean
}

export interface AutoHideScheduler {
  /** 重新开始计时（用户刚有输入 / 控制条刚显示） */
  schedule: () => void
  /** 取消计时（鼠标离开播放器等） */
  cancel: () => void
}

export const createAutoHideScheduler = (deps: AutoHideDeps): AutoHideScheduler => {
  let timer: number | null = null

  const cancel = () => {
    if (timer !== null) {
      deps.clearTimer(timer)
      timer = null
    }
  }

  const schedule = () => {
    cancel()
    timer = deps.setTimer(() => {
      timer = null
      // 到点时再看一次：面板在这期间可能被打开
      if (deps.isBlocked()) return
      deps.onHide()
    }, deps.hideMs)
  }

  return { schedule, cancel }
}
