/**
 * 路由键常量：数据页/完整榜单页/集合子页共用的 kind 与榜单键。
 * 原位于 pages/mock.ts（阶段 A 数据文件），2026-09-03 mock 全量退役后迁移至此；
 * 唯一来源，禁在页面里散写字面量（「禁再散写字面量」纪律沿用）。
 */

/** 集合子页 kind（路由段 /app/collection/:kind/:name） */
export const COLLECTION_TAG = 'tag'
export const COLLECTION_AUTHOR = 'author'

/**
 * 资产详情路由（router.tsx /app 下的 asset/:assetId 段）。
 * 列表卡/榜单/接下来播放等所有进详情的跳转唯一来源——模板串曾散写 9 处/8 文件，
 * 2026-09-07 审查后收口至此，禁再散写字面量。
 */
export function assetDetail(id: string): string {
  return `/app/asset/${id}`
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
