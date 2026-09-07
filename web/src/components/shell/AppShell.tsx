/**
 * 应用壳层：侧栏 + 顶栏 + 内容区（路由出口）+ 右下角悬浮刷新（详情叠加期
 * 隐藏，见下方 overlayOpen 注释）。
 * 真导航（非叠加组内切换）时内容区滚回顶部（原型 showPage 的 scrollTop=0
 * 语义 → 路由 pathname 驱动；叠加组门见下方 E1 注释）。
 */

import { QM_REFRESH_EVENT } from '@/lib/constants'
import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { inHomeDetailGroup, isAssetDetailPath } from '@/lib/route-keys'
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
  // E1 叠加组（首页↔详情）：详情是覆盖在首页底衬上的叠加层，组内互相切换
  // （进详情/浏览器返回/详情→详情）不复位 .content——底衬列表的 scrollTop
  // 即返回时的恢复位置，复位它等于丢态。仅真导航（进出相册/搜索等其他页）
  // 保持原回顶语义。上一 pathname 用 ref 记，避免为比较值多一次渲染。
  const prevPathnameRef = useRef<string | null>(null)

  useEffect(() => {
    const prev = prevPathnameRef.current
    prevPathnameRef.current = pathname
    if (prev !== null && inHomeDetailGroup(prev) && inHomeDetailGroup(pathname)) return
    contentRef.current?.scrollTo(0, 0)
  }, [pathname])

  // 详情叠加打开期间 .content 不再滚动，回顶部按钮失去意义且悬浮在覆盖层上；
  // 刷新 FAB 同门（F7 2026-09-08 用户拍板，推翻 E5「FAB 浮于查看器系有意」
  // 约定）：详情叠加期不渲染——图片查看器只能在详情叠加内打开，叠加期隐藏
  // 即查看器期不可达、不可点
  const overlayOpen = isAssetDetailPath(pathname)
  const showBackTopFab = showBackTop && !overlayOpen

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
      {/* F7：详情叠加期（含其上打开的图片查看器期）条件卸载，不渲染即不可点
          （不用 hidden 属性——.layout .refresh-fab 的 display:flex 会压过
          [hidden] 的 UA display:none，见 prototype.css .page[hidden] 同款坑） */}
      {!overlayOpen && (
        <button
          className={`refresh-fab${spinning ? ' spinning' : ''}`}
          title="刷新"
          type="button"
          onClick={refresh}
          onAnimationEnd={() => setSpinning(false)}
        >
          <RefreshIcon />
        </button>
      )}
      <button
        className={`backtop-fab${showBackTopFab ? ' shown' : ''}`}
        title="回到顶部"
        type="button"
        onClick={backToTop}
        tabIndex={showBackTopFab ? 0 : -1}
      >
        <BackTopIcon />
        <p>顶部</p>
      </button>
    </div>
  )
}
