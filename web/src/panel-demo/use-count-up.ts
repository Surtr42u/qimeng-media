/**
 * 数字滚动 hook（Magic UI count-up 手法，自实现不装新库）。
 *
 * rAF + easeOutCubic 缓动：挂载时从 0 滚向目标，目标值变化时从当前值续滚；
 * 只在挂载与值变化时触发，无循环动画（仪表盘克制原则）。
 */
import { useEffect, useRef, useState } from 'react'

const easeOutCubic = (t: number) => 1 - Math.pow(1 - t, 3)

/**
 * @param target 目标数值（变化即触发续滚）
 * @param duration 动画时长 ms（默认 600）
 * @returns 当前应显示的数值（未取整，展示侧按 decimals 格式化）
 */
export function useCountUp(target: number, duration = 600): number {
  const [display, setDisplay] = useState(0)
  // from 用 ref 记录，避免动画中途重渲染时把起点重置回旧值
  const fromRef = useRef(0)
  const rafRef = useRef(0)

  useEffect(() => {
    const from = fromRef.current
    const start = performance.now()
    const tick = (now: number) => {
      const t = Math.min((now - start) / duration, 1)
      const current = from + (target - from) * easeOutCubic(t)
      fromRef.current = current
      setDisplay(current)
      if (t < 1) {
        rafRef.current = requestAnimationFrame(tick)
      }
    }
    rafRef.current = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(rafRef.current)
  }, [target, duration])

  return display
}
