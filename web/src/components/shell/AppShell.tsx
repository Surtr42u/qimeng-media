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
import { RefreshIcon, BackTopIcon } from './icons'

/** 内容区滚动超过该值（布局像素）显示「回到顶部」按钮（阈值用户授权实现自定） */
const BACK_TOP_THRESHOLD = 400

export function AppShell() {
  const { pathname } = useLocation()
  const contentRef = useRef<HTMLElement>(null)
  const queryClient = useQueryClient()
  const [spinning, setSpinning] = useState(false)
  const [showBackTop, setShowBackTop] = useState(false)

  useEffect(() => {
    contentRef.current?.scrollTo(0, 0)
  }, [pathname])

  // 滚动容器是 .content（window 不滚），显隐跟随其 scrollTop；切页 scrollTo(0,0) 会触发
  // scroll 事件使按钮自动隐藏，已在顶部时无事件且状态本就为隐藏，无泄漏路径
  useEffect(() => {
    const el = contentRef.current
    if (!el) return
    const onScroll = (): void => setShowBackTop(el.scrollTop > BACK_TOP_THRESHOLD)
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [])

  const backToTop = (): void => {
    contentRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
  }

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
      <button
        className={`backtop-fab${showBackTop ? ' shown' : ''}`}
        title="回到顶部"
        type="button"
        onClick={backToTop}
        tabIndex={showBackTop ? 0 : -1}
      >
        <BackTopIcon />
        <p>顶部</p>
      </button>
    </div>
  )
}
