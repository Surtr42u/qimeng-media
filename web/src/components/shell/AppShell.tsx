/**
 * 应用壳层：侧栏 + 顶栏 + 内容区（路由出口）+ 右下角悬浮刷新。
 * 切页时内容区滚回顶部（原型 showPage 的 scrollTop=0 语义 → 路由 pathname 驱动）。
 */

import { QM_REFRESH_EVENT } from '@/lib/constants'
import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'
import { RefreshIcon } from './icons'

export function AppShell() {
  const { pathname } = useLocation()
  const contentRef = useRef<HTMLElement>(null)
  const queryClient = useQueryClient()
  const [spinning, setSpinning] = useState(false)

  useEffect(() => {
    contentRef.current?.scrollTo(0, 0)
  }, [pathname])

  const refresh = (): void => {
    // 真实刷新，两条通道分工：
    // 1) invalidateQueries：失效全部查询缓存，负责普通页面（媒体/人物/设置等）的数据重拉；
    // 2) 'qm:refresh' 全局事件：HomePage 监听此事件执行旧版 refreshSeed++ 全量重排语义
    //    （首页三 tab 数据多与旧值相同，invalidate 后无可见变化，故需要显式重排信号）。
    // 事件名唯一来源 QM_REFRESH_EVENT（lib/constants.ts），与 HomePage 监听方共享。
    void queryClient.invalidateQueries()
    window.dispatchEvent(new CustomEvent(QM_REFRESH_EVENT))
    setSpinning(false)
    requestAnimationFrame(() => setSpinning(true))
  }

  return (
    <div className="layout">
      <Sidebar />
      <div className="main">
        <TopBar />
        <main className="content" ref={contentRef}>
          <Outlet />
        </main>
      </div>
      <button
        className={`refresh-fab${spinning ? ' spinning' : ''}`}
        title="刷新"
        type="button"
        onClick={refresh}
        onAnimationEnd={() => setSpinning(false)}
      >
        <RefreshIcon />
      </button>
    </div>
  )
}
