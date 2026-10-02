/**
 * 应用壳层：侧栏 + 顶栏 + 内容区（路由出口）+ 右下角悬浮刷新（详情叠加期
 * 隐藏，见下方 overlayOpen 注释）。
 * 真导航（非同一叠加对内切换）时内容区滚回顶部（原型 showPage 的 scrollTop=0
 * 语义 → 路由 pathname 驱动；叠加组门见下方 F5 注释）。
 */

import { useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { HOME_PATH, isAssetDetailPath, listKeyFromPath, readBackdropKey } from '@/lib/route-keys'
import { ShellRefreshContext } from './refresh-context'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'
import { RefreshIcon, BackTopIcon } from './icons'

/** 内容区滚动超过该值（布局像素）显示「回到顶部」按钮（阈值用户授权实现自定） */
const BACK_TOP_THRESHOLD = 400

export function AppShell() {
  const { pathname, state: navState } = useLocation()
  const contentRef = useRef<HTMLElement>(null)
  const queryClient = useQueryClient()
  const [spinning, setSpinning] = useState(false)
  const [showBackTop, setShowBackTop] = useState(false)
  // 刷新信号（context 版，原 window CustomEvent 'qm:refresh'）：数值递增即
  // 「刷新按钮被按过一次」，经 ShellRefreshContext 下发给叠加组内的首页 tab
  // （消费方 useShellRefresh——HomePage 推荐/cos 换 seed、热榜重置分页）
  const [refreshTick, setRefreshTick] = useState(0)
  // F5 叠加组滚动门（取代 E1 的 inHomeDetailGroup「双在组内」判定——单组多
  // 列表后「组内」不再等于「同一叠加对」）：详情是覆盖在底衬列表上的叠加层，
  // (1) 进/驻详情（列表→详情、详情→详情）不复位 .content——底衬列表的
  //     scrollTop 即返回时的恢复位置，复位它等于丢态；
  // (2) 离开详情（详情→列表）仅当目标列表就是该详情的底衬（上一条历史条目
  //     state 的 backdrop，缺省 home——home 链入口从不携带该字段）才不复位：
  //     浏览器返回与顶栏/侧栏回底衬都是恢复滚动；底衬以外的目标（如相册详情
  //     侧栏切首页）是真导航 → 复位回顶（2026-09-08 验收矩阵③）；
  // (3) 列表→列表 → 复位（真导航回顶语义不变）。
  // 上一 pathname 与其 state 都用 ref 记：效果运行时 location 已是新条目，
  // 底衬归属只存在于详情条目自身的 state 上。
  const prevPathnameRef = useRef<string | null>(null)
  const prevStateRef = useRef<unknown>(null)

  useEffect(() => {
    const prev = prevPathnameRef.current
    const prevState = prevStateRef.current
    prevPathnameRef.current = pathname
    prevStateRef.current = navState
    if (isAssetDetailPath(pathname)) return
    if (prev !== null && isAssetDetailPath(prev)) {
      const backdrop = readBackdropKey(prevState) ?? HOME_PATH
      if (backdrop === listKeyFromPath(pathname)) return
    }
    contentRef.current?.scrollTo(0, 0)
  }, [pathname, navState])

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
    // 2) ShellRefreshContext tick：HomePage 监听此信号执行旧版 refreshSeed++ 全量重排
    //    语义（首页三 tab 数据多与旧值相同，invalidate 后无可见变化，故需要显式重排
    //    信号——推荐/cos 换 seed 全量重排并回第一页、热榜重置分页重拉；TanStack 的
    //    invalidate/resetQueries 均覆盖不了「同 seed 重取可复现、分页需归零」这两点）。
    void queryClient.invalidateQueries()
    setRefreshTick((n) => n + 1)
    setSpinning(false)
    requestAnimationFrame(() => setSpinning(true))
  }

  return (
    <ShellRefreshContext.Provider value={refreshTick}>
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
    </ShellRefreshContext.Provider>
  )
}
