import { Suspense, lazy } from 'react'
import { createBrowserRouter, Navigate } from 'react-router'
import { AuthGate } from '@/components/layout/AuthGate'
import { AssetOverlayGroupLayout } from '@/components/layout/AssetOverlayGroupLayout'
import { RootLayout } from '@/components/layout/RootLayout'
import { AppShell } from '@/components/shell/AppShell'
import { CollectionDeepLink } from '@/pages/CollectionDeepLink'
import { HOME_PATH } from '@/lib/route-keys'

/**
 * 路由（2026-09-02：UI 原型移植重建；E1 首页↔详情改叠加组）。
 *
 * 页面懒加载（chunk 分包）；壳层结构：
 *   /app（AuthGate 门禁）→ RootLayout（SSE 事件桥）→ AppShell（侧栏+顶栏+内容区）
 *   → 业务页（五主导航页 + 搜索结果/完整榜单/作者管理/集合等子页）
 *   + 列表↔详情叠加组（pathless layout route：首页/相册常驻底衬，详情覆盖
 *   打开，布局元素见 components/layout/AssetOverlayGroupLayout.tsx）。
 * 页面类名与原型逐字一致（styles/prototype.css 消费 #page-* id 选择器）。
 */

const MinePage = lazy(() => import('@/pages/MinePage'))
const DataPage = lazy(() => import('@/pages/DataPage'))
const MaintenancePage = lazy(() => import('@/pages/MaintenancePage'))
const SettingsPage = lazy(() => import('@/pages/SettingsPage'))
const SearchPage = lazy(() => import('@/pages/SearchPage'))
const RanksPage = lazy(() => import('@/pages/RanksPage'))
const AuthorsPage = lazy(() => import('@/pages/AuthorsPage'))
const CollectionPage = lazy(() => import('@/pages/CollectionPage'))
const AssetDetailPage = lazy(() => import('@/pages/AssetDetailPage'))
const AssetEditPage = lazy(() => import('@/pages/AssetEditPage'))
const LibraryManagePage = lazy(() => import('@/pages/LibraryManagePage'))
const LibraryUploadPage = lazy(() => import('@/pages/LibraryUploadPage'))
const LibraryVocabularyPage = lazy(() => import('@/pages/LibraryVocabularyPage'))
const VocabularyEditPage = lazy(() => import('@/pages/VocabularyEditPage'))
const LibraryRegistryPage = lazy(() => import('@/pages/LibraryRegistryPage'))
const AuthorTxtImportPage = lazy(() => import('@/pages/AuthorTxtImportPage'))
const BackupImportExportPage = lazy(() => import('@/pages/BackupImportExportPage'))
const TrashPage = lazy(() => import('@/pages/TrashPage'))

export const router = createBrowserRouter([
  // 根路径直达首页（应用主入口；路由串走 HOME_PATH 常量，F2 字面量收敛）
  { index: true, element: <Navigate to={HOME_PATH} replace /> },
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
                // F5 列表↔详情叠加组（pathless layout route：无 path 的路由对象
                // 只为 children 提供布局嵌套、不产生 URL 段——react-router 官方
                // Layout Routes 语法；由 E1 首页单列表泛化为多列表单槽，布局见
                // components/layout/AssetOverlayGroupLayout.tsx）。组内列表页
                // 在同一布局槽恒挂载为底衬：列表↔详情互切不卸载，筛选态/seed/
                // 已加载分页与 .content 滚动位置自然保留（返回保态的根基）；
                // 详情子路由渲染为覆盖层（.asset-overlay），URL 仍是
                // /app/asset/:assetId 可直达（直达无 state 时底衬=首页）。
                // 列表入组两处登记缺一不可：OVERLAY_LIST_PATHS（route-keys）
                // + 布局槽位条件渲染；子路由 element:null，可见内容就是底衬
                // 列表本身。
                element: <AssetOverlayGroupLayout />,
                children: [
                  { path: 'home', element: null },
                  { path: 'albums', element: null },
                  { path: 'asset/:assetId', element: <Suspense fallback={null}><AssetDetailPage /></Suspense> },
                ],
              },
              { path: 'mine', element: <Suspense fallback={null}><MinePage /></Suspense> },
              { path: 'data', element: <Suspense fallback={null}><DataPage /></Suspense> },
              { path: 'maintenance', element: <Suspense fallback={null}><MaintenancePage /></Suspense> },
              { path: 'settings', element: <Suspense fallback={null}><SettingsPage /></Suspense> },
              { path: 'search', element: <Suspense fallback={null}><SearchPage /></Suspense> },
              { path: 'ranks/:rank', element: <Suspense fallback={null}><RanksPage /></Suspense> },
              { path: 'authors', element: <Suspense fallback={null}><AuthorsPage /></Suspense> },
              // 资产编辑子页（详情页「编辑」进入）：与叠加组 asset/:assetId 不同形
              // 不冲突——edit 静态子段排名更优，详情叠加只吞不带 /edit 的精确路径；
              // 平铺在 AppShell 下（同 maintenance/* 风格），不在叠加组内
              { path: 'asset/:assetId/edit', element: <Suspense fallback={null}><AssetEditPage /></Suspense> },
              // 查询串深链归一（?author=/?tag= → 路径形态，见 CollectionDeepLink）：
              // 未注册裸 /app/collection 时这类外部深链整树无匹配 → 白屏（F1）
              { path: 'collection', element: <CollectionDeepLink /> },
              { path: 'collection/:kind/:name', element: <Suspense fallback={null}><CollectionPage /></Suspense> },
              // 数据管理 hub + 子页（2026-09-17 原单页「文件管理」拆分，扁平同层
              // 注册与 maintenance/* 既有风格一致；页面内容零改动只做信息架构拆分。
              // 2026-09-29 增 vocabulary 子页：来源词表/作者镜像自上传页再拆独立页）
              { path: 'maintenance/files', element: <Suspense fallback={null}><LibraryManagePage /></Suspense> },
              { path: 'maintenance/files/upload', element: <Suspense fallback={null}><LibraryUploadPage /></Suspense> },
              { path: 'maintenance/files/vocabulary', element: <Suspense fallback={null}><LibraryVocabularyPage /></Suspense> },
              { path: 'maintenance/files/vocabulary-edit', element: <Suspense fallback={null}><VocabularyEditPage /></Suspense> },
              { path: 'maintenance/files/libraries', element: <Suspense fallback={null}><LibraryRegistryPage /></Suspense> },
              { path: 'maintenance/files/authors-txt', element: <Suspense fallback={null}><AuthorTxtImportPage /></Suspense> },
              { path: 'maintenance/files/backup', element: <Suspense fallback={null}><BackupImportExportPage /></Suspense> },
              { path: 'maintenance/trash', element: <Suspense fallback={null}><TrashPage /></Suspense> },
              // 未注册路径兜底（E/F卷审查·P3 清偿）：F1 只修了 /app/collection 深链，
              // 任意其他未注册路径（旧书签/手误）仍整树无匹配白屏且 ErrorBoundary
              // 兜不住（路由不匹配不抛异常）；catch-all 静默回首页（SPA 无 404 页需求）
              { path: '*', element: <Navigate to={HOME_PATH} replace /> },
            ],
          },
        ],
      },
    ],
  },
])

