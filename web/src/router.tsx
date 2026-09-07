import { Suspense, lazy } from 'react'
import { createBrowserRouter, Navigate } from 'react-router'
import { AuthGate } from '@/components/layout/AuthGate'
import { HomeBackdropLayout } from '@/components/layout/HomeBackdropLayout'
import { RootLayout } from '@/components/layout/RootLayout'
import { AppShell } from '@/components/shell/AppShell'
import { CollectionDeepLink } from '@/pages/CollectionDeepLink'

/**
 * 路由（2026-09-02：UI 原型移植重建；E1 首页↔详情改叠加组）。
 *
 * 页面懒加载（chunk 分包）；壳层结构：
 *   /app（AuthGate 门禁）→ RootLayout（SSE 事件桥）→ AppShell（侧栏+顶栏+内容区）
 *   → 九个业务页（六主导航页 + 搜索结果/完整榜单/作者管理三个子页）
 *   + 首页↔详情叠加组（pathless layout route：HomePage 常驻底衬，详情覆盖打开，
 *   布局元素见 components/layout/HomeBackdropLayout.tsx）。
 * 页面类名与原型逐字一致（styles/prototype.css 消费 #page-* id 选择器）。
 */

const AlbumsPage = lazy(() => import('@/pages/AlbumsPage'))
const MinePage = lazy(() => import('@/pages/MinePage'))
const DataPage = lazy(() => import('@/pages/DataPage'))
const MaintenancePage = lazy(() => import('@/pages/MaintenancePage'))
const SettingsPage = lazy(() => import('@/pages/SettingsPage'))
const SearchPage = lazy(() => import('@/pages/SearchPage'))
const RanksPage = lazy(() => import('@/pages/RanksPage'))
const AuthorsPage = lazy(() => import('@/pages/AuthorsPage'))
const CollectionPage = lazy(() => import('@/pages/CollectionPage'))
const AssetDetailPage = lazy(() => import('@/pages/AssetDetailPage'))
const LibraryManagePage = lazy(() => import('@/pages/LibraryManagePage'))
const TrashPage = lazy(() => import('@/pages/TrashPage'))

export const router = createBrowserRouter([
  // 根路径直达首页（应用主入口）
  { index: true, element: <Navigate to="/app/home" replace /> },
  {
    path: 'app',
    element: <AuthGate />,
    children: [
      {
        // 鉴权后内容层：事件桥 + 壳层 + 路由出口
        element: <RootLayout />,
        children: [
          {
            element: <AppShell />,
            children: [
              {
                // E1 首页↔详情叠加组（pathless layout route：无 path 的路由对象
                // 只为 children 提供布局嵌套、不产生 URL 段——react-router 官方
                // Layout Routes 语法）。HomePage 在组内恒挂载为底衬：home↔asset
                // 切换不卸载，seed/已加载分页与 .content 滚动位置自然保留（返回
                // 保态的根基）；详情子路由渲染为覆盖层（.asset-overlay），URL 仍是
                // /app/asset/:assetId 可直达（直达时底衬=首页）。
                element: <HomeBackdropLayout />,
                children: [
                  // 首页子路由不渲染元素：可见内容就是底衬 HomePage 本身
                  { path: 'home', element: null },
                  { path: 'asset/:assetId', element: <Suspense fallback={null}><AssetDetailPage /></Suspense> },
                ],
              },
              { path: 'albums', element: <Suspense fallback={null}><AlbumsPage /></Suspense> },
              { path: 'mine', element: <Suspense fallback={null}><MinePage /></Suspense> },
              { path: 'data', element: <Suspense fallback={null}><DataPage /></Suspense> },
              { path: 'maintenance', element: <Suspense fallback={null}><MaintenancePage /></Suspense> },
              { path: 'settings', element: <Suspense fallback={null}><SettingsPage /></Suspense> },
              { path: 'search', element: <Suspense fallback={null}><SearchPage /></Suspense> },
              { path: 'ranks/:rank', element: <Suspense fallback={null}><RanksPage /></Suspense> },
              { path: 'authors', element: <Suspense fallback={null}><AuthorsPage /></Suspense> },
              // 查询串深链归一（?author=/?tag= → 路径形态，见 CollectionDeepLink）：
              // 未注册裸 /app/collection 时这类外部深链整树无匹配 → 白屏（F1）
              { path: 'collection', element: <CollectionDeepLink /> },
              { path: 'collection/:kind/:name', element: <Suspense fallback={null}><CollectionPage /></Suspense> },
              { path: 'maintenance/files', element: <Suspense fallback={null}><LibraryManagePage /></Suspense> },
              { path: 'maintenance/trash', element: <Suspense fallback={null}><TrashPage /></Suspense> },
            ],
          },
        ],
      },
    ],
  },
])

