/**
 * 路由键常量：数据页/完整榜单页/集合子页共用的 kind 与榜单键。
 * 原位于 pages/mock.ts（阶段 A 数据文件），2026-09-03 mock 全量退役后迁移至此；
 * 唯一来源，禁在页面里散写字面量（「禁再散写字面量」纪律沿用）。
 */

import { generatePath, matchPath } from 'react-router'

/** 首页路由（叠加组底衬列表键；F2（2026-09-08）把 Sidebar/TopBar/router 的
 *  存量字面量收敛至此，本文件是路由键唯一来源，新增引用一律走此常量） */
export const HOME_PATH = '/app/home'

/** 相册页路由（F5 列表↔详情叠加组第二个底衬列表键；Sidebar/后续引用一律走此常量） */
export const ALBUMS_PATH = '/app/albums'

/** 我的页路由（Sidebar 主导航第三项；2026-09-20 全库审查：Sidebar 注释宣称
 *  路由串全走本文件常量，实际 mine/data/maintenance/settings 四处仍是字面量，收口） */
export const MINE_PATH = '/app/mine'

/** 数据页路由（Sidebar 主导航第四项） */
export const DATA_PATH = '/app/data'

/** 维护页路由（Sidebar 底部图标组入口） */
export const MAINTENANCE_PATH = '/app/maintenance'

/** 设置页路由（Sidebar 底部图标组入口） */
export const SETTINGS_PATH = '/app/settings'

/** 数据管理 hub 路由（库管理/上传/来源词表/作者导入/备份五个子页的
 *  「← 返回数据管理」共用入口；曾散写 5 处，2026-09-17 全检后收口至此，
 *  子页路径字面量归 router.tsx） */
export const MAINTENANCE_FILES_PATH = '/app/maintenance/files'

/** 来源词表子页路由（2026-09-29 自上传文件页拆出独立页；上传页指引链接与
 *  hub 入口卡共用此常量） */
export const MAINTENANCE_FILES_VOCABULARY_PATH = '/app/maintenance/files/vocabulary'

/** 词表维护子页路由（2026-10-04 App ADR-0035「词表维护」web 移植：出处组与
 *  停用词的检索词层直接编辑；hub 入口卡与页内返回共用此常量） */
export const MAINTENANCE_FILES_VOCAB_EDIT_PATH = '/app/maintenance/files/vocabulary-edit'

/** 集合子页 kind（路由段 /app/collection/:kind/:name） */
export const COLLECTION_TAG = 'tag'
export const COLLECTION_AUTHOR = 'author'

/**
 * 集合子页路由模板（router.tsx /app 下 collection/:kind/:name 段同形；与
 * ASSET_DETAIL_PATTERN 同例——模板串全库仅此一处，新增引用一律走 collectionPath）。
 */
export const COLLECTION_PATTERN = '/app/collection/:kind/:name'

/**
 * 集合子页路由（kind 传 COLLECTION_TAG/COLLECTION_AUTHOR 常量，name=标签名或
 * 作者显示名）。name 由 generatePath 内部 percent-encode（中文/空格/斜杠安全，
 * useParams 读回自动解码），调用方禁止自行 encodeURIComponent 以免双重编码。
 */
export function collectionPath(kind: string, name: string): string {
  return generatePath(COLLECTION_PATTERN, { kind, name })
}

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
 * 资产编辑路由模板（router.tsx /app 下 asset/:assetId/edit 段同形；模板串
 * 全库仅此一处）。与叠加组详情路由 asset/:assetId 不同形不冲突：edit 是
 * 静态子段、路由排名更优，只吞 /app/asset/:assetId/edit 自己。
 */
export const ASSET_EDIT_PATTERN = '/app/asset/:assetId/edit'

/**
 * 资产编辑路由（详情页「编辑」按钮跳转唯一来源，禁散写字面量）。
 * assetId 由 generatePath 内部 percent-encode，与 assetDetail 同口径。
 */
export function assetEdit(id: string): string {
  return generatePath(ASSET_EDIT_PATTERN, { assetId: id })
}

/**
 * 当前 pathname 是否资产详情（E1 叠加层打开态判定：router 叠加布局的底衬
 * inert 与 AppShell 的滚动复位门/回顶部按钮显隐共用，单一来源）
 */
export function isAssetDetailPath(pathname: string): boolean {
  return matchPath(ASSET_DETAIL_PATTERN, pathname) !== null
}

/**
 * F5 叠加组底衬列表身份键集合（pathname 即键）。列表页入叠加组时在此登记，
 * 并同步 AssetOverlayGroupLayout 的槽位条件渲染（两处缺一即白屏/回落错误列表）。
 * 带路径参数的列表（collection/:kind/:name、ranks/:rank）后续批入组时在本
 * 函数内扩展匹配，勿直接塞常量数组（参数段不是固定字符串）。
 */
const OVERLAY_LIST_PATHS: readonly string[] = [HOME_PATH, ALBUMS_PATH]

/**
 * pathname → 叠加组列表身份键（键=pathname 本身，与详情侧 readBackdropKey
 * 同一口径）；非组内列表路径返回 null——/app/asset 详情与组外页面都不是
 * 底衬列表。叠加布局的底衬归属：列表路径走本函数派生；详情走 readBackdropKey。
 */
export function listKeyFromPath(pathname: string): string | null {
  return OVERLAY_LIST_PATHS.includes(pathname) ? pathname : null
}

/**
 * E5 列表上下文快照（history state）：叠加组入口（HomePage.openDetail、
 * UpNextList 行与 F5 起的 AlbumsPage 卡片）进详情时携带「当前已加载流」的
 * id 快照 + 所点下标，详情页据此渲染上一件/下一件批次导航与查看器切换；
 * 其余入口不传 state——直达/刷新 readAssetNavState 返回 null，导航 UI 不
 * 渲染（无上下文兜底）。
 * origUrls：与 ids 对齐的可选原件直链快照，仅供查看器相邻预载。现有列表类型
 * （AssetSummary）无 origUrl 字段故各入口都不传——邻项不预载、切换时用
 * 详情接口的 origUrl（短暂加载态，E5 拍板允许）；字段为未来带原件直链的
 * 列表入口预留，勿删。
 */
export interface AssetNavState {
  ids: string[]
  index: number
  origUrls?: Array<string | undefined>
}

/**
 * F5 叠加组详情入口 state 总形：批次导航快照 + 顶层独立 backdrop 字段
 * （底衬列表身份键）。backdrop 不扩入 AssetNavState 的理由：两者职责与
 * 生命周期不同——快照缺失=无 pager（无缺省值），backdrop 缺失有安全缺省
 * home；且 readAssetNavState 的纪律是「任一字段不合法整包拒绝」，混入允许
 * 缺省回落的字段要么让非法 backdrop 拖垮 pager、要么破坏整包拒绝口径。
 */
export type OverlayDetailState = AssetNavState & { backdrop?: string }

/**
 * location.state 收敛解析（全站首个 history state 消费点）：state 运行时
 * 不可信（手工构造/扩展注入），逐字段校验，任一不合法一律按无上下文处理。
 */
export function readAssetNavState(state: unknown): AssetNavState | null {
  if (typeof state !== 'object' || state === null) return null
  const ids = (state as { ids?: unknown }).ids
  const index = (state as { index?: unknown }).index
  const origUrls = (state as { origUrls?: unknown }).origUrls
  if (!Array.isArray(ids) || !ids.every((id): id is string => typeof id === 'string')) return null
  if (typeof index !== 'number' || !Number.isInteger(index) || index < 0 || index >= ids.length) {
    return null
  }
  if (
    origUrls !== undefined &&
    (!Array.isArray(origUrls) || !origUrls.every((u) => u === undefined || typeof u === 'string'))
  ) {
    return null
  }
  return { ids, index, origUrls: origUrls as Array<string | undefined> | undefined }
}

/**
 * F5 底衬字段读取（location.state 顶层独立字段，独立校验，不与
 * readAssetNavState 耦合）：返回入口列表身份键或 null（未携带/不合法）。
 * 消费方各自定缺省：叠加布局 null → home（深链直达与 home 链入口都不携带
 * 该字段）；goNeighbor/UpNextList null → 不写该字段（state 与 E1 现状
 * 逐字节一致，home 链零回归）。校验必须是已登记的组内列表键，非法值一律
 * null 不猜。
 */
export function readBackdropKey(state: unknown): string | null {
  if (typeof state !== 'object' || state === null) return null
  const backdrop = (state as { backdrop?: unknown }).backdrop
  return typeof backdrop === 'string' ? listKeyFromPath(backdrop) : null
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
