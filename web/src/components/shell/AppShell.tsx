/**
 * 应用壳层：侧栏 + 顶栏 + 内容区（路由出口）+ 右下角悬浮刷新。
 * 切页时内容区滚回顶部（原型 showPage 的 scrollTop=0 语义 → 路由 pathname 驱动）。
 */

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
    // 真实刷新：失效全部查询缓存（阶段 B 数据接入后生效；纯 mock 页面仅旋转反馈）
    void queryClient.invalidateQueries()
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
