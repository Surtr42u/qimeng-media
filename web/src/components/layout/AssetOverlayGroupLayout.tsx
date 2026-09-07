/**
 * F5 列表↔详情叠加组的布局路由元素（router.tsx 消费；由 E1 的
 * HomeBackdropLayout 泛化为多列表单槽，E1 同日改名本文件）。
 *
 * 全部「列表页 ↔ 资产详情」叠加对共用同一个 pathless 组：列表子路由
 * element:null，可见内容就是本布局按 activeKey 渲染的单个底衬槽；详情子路由
 * 经 Outlet 渲染为覆盖层（styles/prototype.css 的 .asset-overlay）。
 *
 * 单组单槽是 /app/asset/:assetId 路由唯一（不能每列表复制路由）下的正解：
 * 同一 tree 位置条件渲染同类型组件，activeKey 稳定期（列表↔自己的详情互切）
 * 底衬列表不卸载——组件态（相册四维筛选/换一批 seed）与 React Query 已加载
 * 页、.content 滚动位置自然保留，返回保态的根基；同时最多 1 个列表挂载，
 * 内存与真导航持平。
 *
 * activeKey 推导：
 *   - pathname 是组内列表路径 → pathname 派生（listKeyFromPath；非组内路径
 *     null → 槽渲染 null，组配置错误显形而非静默回落）；
 *   - pathname 是详情 → 入口 location.state.backdrop（readBackdropKey 校验，
 *     缺省/不合法回落 home：深链直达与 home 链入口都不携带该字段）。
 *
 * 独立成文件的原因：router.tsx 同时导出非组件的 router 常量，组件与其同文件
 * 会触发 react-refresh(only-export-components)。
 */

import { Suspense, lazy } from 'react'
import { Outlet, useLocation } from 'react-router'
import {
  ALBUMS_PATH,
  HOME_PATH,
  isAssetDetailPath,
  listKeyFromPath,
  readBackdropKey,
} from '@/lib/route-keys'

const HomePage = lazy(() => import('@/pages/HomePage'))
const AlbumsPage = lazy(() => import('@/pages/AlbumsPage'))

export function AssetOverlayGroupLayout() {
  const { pathname, state } = useLocation()
  const overlayOpen = isAssetDetailPath(pathname)
  const activeKey = overlayOpen
    ? (readBackdropKey(state) ?? HOME_PATH)
    : listKeyFromPath(pathname)
  return (
    <>
      {/* 详情打开期间底衬槽 inert：覆盖层不透明本就点不到，主要防 Tab 焦点
          钻入不可见列表（React 19 布尔 inert） */}
      <div className="overlay-backdrop" inert={overlayOpen}>
        {activeKey === HOME_PATH ? (
          <Suspense fallback={null}><HomePage /></Suspense>
        ) : activeKey === ALBUMS_PATH ? (
          <Suspense fallback={null}><AlbumsPage /></Suspense>
        ) : null}
      </div>
      <Outlet />
    </>
  )
}
