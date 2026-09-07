/**
 * 路由键常量：数据页/完整榜单页/集合子页共用的 kind 与榜单键。
 * 原位于 pages/mock.ts（阶段 A 数据文件），2026-09-03 mock 全量退役后迁移至此；
 * 唯一来源，禁在页面里散写字面量（「禁再散写字面量」纪律沿用）。
 */

import { generatePath, matchPath } from 'react-router'

/** 首页路由（E1 叠加组底衬判定用；Sidebar/TopBar 存量字面量待收敛，本文件
 *  是路由键唯一来源，新增引用一律走此常量） */
export const HOME_PATH = '/app/home'

/** 集合子页 kind（路由段 /app/collection/:kind/:name） */
export const COLLECTION_TAG = 'tag'
export const COLLECTION_AUTHOR = 'author'

/**
 * 资产详情路由匹配模式（router.tsx 叠加组子路由段、assetDetail() 派生与
 * isAssetDetailPath 判定的单一来源——模板串全库仅此一处）。
 */
export const ASSET_DETAIL_PATTERN = '/app/asset/:assetId'

/**
 * 资产详情路由（router.tsx /app 下的 asset/:assetId 段）。
 * 列表卡/榜单/接下来播放等所有进详情的跳转唯一来源——模板串曾散写 9 处/8 文件，
 * 2026-09-07 审查后收口至此，禁再散写字面量。
 */
export function assetDetail(id: string): string {
  return generatePath(ASSET_DETAIL_PATTERN, { assetId: id })
}

/**
 * 资产详情路由 + 原样携带查询串（E1 叠加组导航唯一入口：首页三 tab 的
 * openDetail 与详情右栏 upnext 行都走它）。search 传 location.search 原文
 * （'' 或 '?tab=cos' 这类）——?tab/?period 随行使底衬 HomePage 在叠加打开、
 * 详情→详情、浏览器返回全程保持同一条流，换一批 seed 不丢；空串不加 ?。
 */
export function assetDetailWithSearch(id: string, search: string): string {
  return `${assetDetail(id)}${search}`
}

/**
 * 当前 pathname 是否资产详情（E1 叠加层打开态判定：router 叠加布局的底衬
 * inert 与 AppShell 的滚动复位门/回顶部按钮显隐共用，单一来源）
 */
export function isAssetDetailPath(pathname: string): boolean {
  return matchPath(ASSET_DETAIL_PATTERN, pathname) !== null
}

/** E1 叠加组（首页↔资产详情）判定：组内互相切换时 AppShell 不做滚动复位——
 *  底衬列表的 .content scrollTop 即返回（浏览器回退/顶栏回首页）时的恢复位置 */
export function inHomeDetailGroup(pathname: string): boolean {
  return pathname === HOME_PATH || isAssetDetailPath(pathname)
}

/** 榜单键：DataPage 入口与 RanksPage 路由段共用 */
export const RANK_CONTENT = 'content'
export const RANK_TAGS = 'tags'
export const RANK_AUTHORS = 'authors'

/** 完整榜单页标题映射（数据页「查看全部」按 data-rank 进入对应榜） */
export const RANK_PAGE_TITLES: Record<string, string> = {
  [RANK_CONTENT]: '内容榜',
  [RANK_TAGS]: '标签榜',
  [RANK_AUTHORS]: '作者榜',
}
