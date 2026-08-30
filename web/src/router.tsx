import { createBrowserRouter, Navigate } from 'react-router'
import { AuthGate } from '@/components/layout/AuthGate'
import { AppShell } from '@/components/layout/AppShell'
import { RootLayout } from '@/components/layout/RootLayout'

/**
 * 路由骨架（M2 基建层）。
 *
 * 结构说明（两条 layout）：
 * - AuthGate：鉴权门禁，路由最外层（未鉴权渲染 LoginGate）
 * - RootLayout：鉴权后内容层——SseBridge（应用级事件失效桥）+ 路由出口
 * - TabLayout：AppShell（顶部品牌栏 + 底部 5 Tab）——Tab 页统一在这层下；
 *   详情页 /detail/:assetId 是覆盖页：不进底部 Tab、沉浸全屏，所以挂在
 *   RootLayout 下而不是 TabLayout 下（否则会带上底部导航）。
 *
 * 页面状态约定：Tab 页的筛选/搜索/排序状态存 URL searchParams（见 AppShell 注释），
 * 刷新不丢、Tab 切换回来自动恢复。
 */

/** 路由懒加载包装：react-router v8 route.lazy 期望模块导出 { Component } */
function lazyPage(importer: () => Promise<{ default: React.ComponentType }>) {
  return async () => {
    const mod = await importer()
    return { Component: mod.default }
  }
}

export const router = createBrowserRouter([
  {
    // 管理面板（Tremor Raw 组件 + mock 数据，已拍板为正式 UI 结构）：
    // 与 AuthGate 平级的顶层分支，不走登录门禁；正式植入位置待后续迁移
    path: 'panel-demo',
    lazy: lazyPage(() => import('@/panel-demo/PanelLayout')),
    children: [
      // 五项主导航：首页（推荐流）/ 相册（全量浏览）/ 我的（数据+历史）/ 维护（性能+工具）/ 设置
      { index: true, lazy: lazyPage(() => import('@/panel-demo/HomePage')) },
      { path: 'gallery', lazy: lazyPage(() => import('@/panel-demo/GalleryPage')) },
      { path: 'mine', lazy: lazyPage(() => import('@/panel-demo/MinePage')) },
      { path: 'maintenance', lazy: lazyPage(() => import('@/panel-demo/MaintenancePage')) },
      { path: 'settings', lazy: lazyPage(() => import('@/panel-demo/SettingsPage')) },
      // 旧 /data 索引路由：数据总览已并入「我的」页数据 Tab，重定向保旧链接兼容
      { path: 'data', element: <Navigate to="/panel-demo/mine" replace /> },
      // 榜单详情页（保留）：我的页排行卡「查看全部」进入（type ∈ content|tags|authors）
      { path: 'data/rank/:type', lazy: lazyPage(() => import('@/panel-demo/RankPage')) },
      // 内容浏览页（保留）：榜单条目浏览（kind ∈ content|tag|author，id = 条目名）
      { path: 'data/browse/:kind/:id', lazy: lazyPage(() => import('@/panel-demo/BrowsePage')) },
    ],
  },
  {
    // AuthGate：无 UI，只做放行/门禁（其内部渲染 Outlet）
    element: <AuthGate />,
    children: [
      {
        // 鉴权后内容层：事件桥 + 路由出口
        element: <RootLayout />,
        children: [
          {
            // TabLayout：5 Tab 页统一外壳（首页/全部/相册/统计/管理）
            element: <AppShell />,
            children: [
              { index: true, lazy: lazyPage(() => import('@/pages/RecommendPage')) },
              { path: 'all', lazy: lazyPage(() => import('@/pages/AllAssetsPage')) },
              { path: 'albums', lazy: lazyPage(() => import('@/pages/AlbumsPage')) },
              { path: 'stats', lazy: lazyPage(() => import('@/pages/StatsPage')) },
              { path: 'admin', lazy: lazyPage(() => import('@/pages/AdminPage')) },
              // 管理页下的两个工具子页（不进底部 Tab，从管理页入口进入）
              { path: 'admin/organize', lazy: lazyPage(() => import('@/pages/OrganizePage')) },
              { path: 'admin/trash', lazy: lazyPage(() => import('@/pages/TrashPage')) },
            ],
          },
          {
            // 详情覆盖页：无 AppShell（沉浸全屏），作为顶层路由
            path: 'detail/:assetId',
            lazy: lazyPage(() => import('@/pages/DetailPage')),
          },
        ],
      },
    ],
  },
])
