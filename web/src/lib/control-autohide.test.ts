import { describe, expect, it, vi } from 'vitest'
import { createAutoHideScheduler } from './control-autohide'

const make = (blocked = false) => {
  vi.useFakeTimers()
  const onHide = vi.fn()
  let isBlocked = blocked
  const scheduler = createAutoHideScheduler({
    hideMs: 3000,
    setTimer: (fn, ms) => setTimeout(fn, ms) as unknown as number,
    clearTimer: (id) => clearTimeout(id as unknown as ReturnType<typeof setTimeout>),
    onHide,
    isBlocked: () => isBlocked,
  })
  return { scheduler, onHide, setBlocked: (v: boolean) => (isBlocked = v) }
}

describe('control auto-hide scheduler', () => {
  it('空闲到点后收起', () => {
    const { scheduler, onHide } = make()
    scheduler.schedule()
    vi.advanceTimersByTime(2999)
    expect(onHide).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(onHide).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('面板打开期间到点不收起', () => {
    const { scheduler, onHide } = make(true)
    scheduler.schedule()
    vi.advanceTimersByTime(5000)
    expect(onHide).not.toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('面板关闭后重新计时才会收起（关键回归：不能因旧计时器残留而永不收起）', () => {
    const { scheduler, onHide, setBlocked } = make(true)
    scheduler.schedule()
    vi.advanceTimersByTime(5000)
    expect(onHide).not.toHaveBeenCalled()
    setBlocked(false)
    scheduler.schedule()
    vi.advanceTimersByTime(3000)
    expect(onHide).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('重复 schedule 重置计时，不会提前收起', () => {
    const { scheduler, onHide } = make()
    scheduler.schedule()
    vi.advanceTimersByTime(2000)
    scheduler.schedule()
    vi.advanceTimersByTime(2000)
    expect(onHide).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1000)
    expect(onHide).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('cancel 之后不再收起', () => {
    const { scheduler, onHide } = make()
    scheduler.schedule()
    scheduler.cancel()
    vi.advanceTimersByTime(10_000)
    expect(onHide).not.toHaveBeenCalled()
    vi.useRealTimers()
  })
})
