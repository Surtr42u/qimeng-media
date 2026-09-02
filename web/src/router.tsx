import { Suspense, lazy } from 'react'
import { createBrowserRouter, Navigate } from 'react-router'
import { AuthGate } from '@/components/layout/AuthGate'
import { RootLayout } from '@/components/layout/RootLayout'
import { AppShell } from '@/components/shell/AppShell'

/**
 * 路由（2026-09-02：UI 原型移植重建）。
 *
 * 页面懒加载（chunk 分包）；壳层结构：
 *   /app（AuthGate 门禁）→ RootLayout（SSE 事件桥）→ AppShell（侧栏+顶栏+内容区）
 *   → 九个业务页（六主导航页 + 搜索结果/完整榜单/作者管理三个子页）。
 * 页面类名与原型逐字一致（styles/prototype.css 消费 #page-* id 选择器）。
 */

const HomePage = lazy(() => import('@/pages/HomePage'))
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
              { path: 'home', element: <Suspense fallback={null}><HomePage /></Suspense> },
              { path: 'albums', element: <Suspense fallback={null}><AlbumsPage /></Suspense> },
              { path: 'mine', element: <Suspense fallback={null}><MinePage /></Suspense> },
              { path: 'data', element: <Suspense fallback={null}><DataPage /></Suspense> },
              { path: 'maintenance', element: <Suspense fallback={null}><MaintenancePage /></Suspense> },
              { path: 'settings', element: <Suspense fallback={null}><SettingsPage /></Suspense> },
              { path: 'search', element: <Suspense fallback={null}><SearchPage /></Suspense> },
              { path: 'ranks/:rank', element: <Suspense fallback={null}><RanksPage /></Suspense> },
              { path: 'authors', element: <Suspense fallback={null}><AuthorsPage /></Suspense> },
              { path: 'collection/:kind/:name', element: <Suspense fallback={null}><CollectionPage /></Suspense> },
              { path: 'asset/:assetId', element: <Suspense fallback={null}><AssetDetailPage /></Suspense> },
              { path: 'maintenance/files', element: <Suspense fallback={null}><LibraryManagePage /></Suspense> },
              { path: 'maintenance/trash', element: <Suspense fallback={null}><TrashPage /></Suspense> },
            ],
          },
        ],
      },
    ],
  },
])
