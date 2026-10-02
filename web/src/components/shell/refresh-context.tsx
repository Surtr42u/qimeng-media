/**
 * 壳层刷新信号（React context 版，2026-10-02 手搓排查替换原 window CustomEvent
 * 'qm:refresh'）：AppShell 刷新 FAB 递增 tick，叠加组内的首页 tab（推荐/cos/
 * 热榜）消费 tick 做旧版 refreshSeed++ 全量重排语义（换 seed / 重置分页）——
 * invalidateQueries 覆盖不了这两件事（同 seed 重取可复现、分页不归零），见
 * AppShell.refresh 注释。组件间通道走 React 自带机制（context），不再自造
 * window 事件总线（免全局命名空间与手写监听装卸）。
 */

import { createContext, useContext, useEffect, useRef } from 'react'

/** 刷新信号：数值即「刷新按钮被按过的次数」（0 = 尚未按过）。AppShell 是唯一生产方。 */
export const ShellRefreshContext = createContext(0)

/**
 * 订阅壳层刷新：tick 变化即回调一次（挂载与 StrictMode 双跑不触发——
 * prevTickRef 比对，tick 值未前进就不回调；tab 切换重挂时读到的是最新 tick
 * 且不会误触发，与原 window 事件「只在按下的那一刻派发」的时序语义一致）。
 * onRefresh 经 ref 转发：调用方传内联箭头（每次渲染新引用）不会反复触发。
 */
export function useShellRefresh(onRefresh: () => void): void {
  const tick = useContext(ShellRefreshContext)
  const handlerRef = useRef(onRefresh)
  useEffect(() => {
    handlerRef.current = onRefresh
  })
  const prevTickRef = useRef(tick)
  useEffect(() => {
    if (prevTickRef.current === tick) return
    prevTickRef.current = tick
    handlerRef.current()
  }, [tick])
}
