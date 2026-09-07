/**
 * E1 首页↔详情叠加组的布局路由元素（router.tsx 消费）。
 * HomePage 恒在组内同一位置挂载为底衬：home↔asset 子路由切换不卸载它，
 * 组件态（换一批 seed）与 React Query 已加载页不丢——返回保态的根基；
 * 详情子路由经 Outlet 渲染为覆盖层（styles/prototype.css 的 .asset-overlay）。
 * 独立成文件的原因：router.tsx 同时导出非组件的 router 常量，组件与其同文件
 * 会触发 react-refresh(only-export-components)。
 */

import { Suspense, lazy } from 'react'
import { Outlet, useLocation } from 'react-router'
import { isAssetDetailPath } from '@/lib/route-keys'

const HomePage = lazy(() => import('@/pages/HomePage'))

export function HomeBackdropLayout() {
  const { pathname } = useLocation()
  const overlayOpen = isAssetDetailPath(pathname)
  return (
    <>
      {/* 详情打开期间底衬 inert：覆盖层不透明本就点不到，主要防 Tab 焦点
          钻入不可见列表（React 19 布尔 inert） */}
      <div className="home-backdrop" inert={overlayOpen}>
        <Suspense fallback={null}><HomePage /></Suspense>
      </div>
      <Outlet />
    </>
  )
}
