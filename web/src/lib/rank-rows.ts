/**
 * 榜单行加工口径（单一来源；数据页 Top5 与完整榜单页全量共用，此前两页各自
 * 双写同一套排序/过滤）。行结构直接喂 RankRowList（去可选 sub 的最小形态）。
 * 口径来源：DOMAIN_RULES——标签榜按关联文件数；作者榜 = 常看作者。
 * 纯函数：不触网、不改入参（slice 后排序），行为与原页内实现逐字一致。
 */

import type { Author, Tag } from '@/api/generated'
import { LOCALE_ZH } from '@/lib/constants'
import { authorDisplayName } from '@/lib/format'

/** 榜单行（count 已格式化；可直接赋 RankRowList 的 RankRow，sub 省略） */
export type RankRowData = { name: string; count: string }

/**
 * 标签榜：全量按 fileCount 降序，行计数 = 关联文件数。
 * 返回全量行，TOP 数量由调用方自控 slice（数据页 Top5 / 榜单页全量）。
 */
export function tagRankRows(tags: Tag[]): RankRowData[] {
  return tags
    .slice()
    .sort((a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0))
    .map((t) => ({ name: t.name ?? '', count: String(t.fileCount ?? 0) }))
}

/**
 * 作者榜 = 常看作者（原型 topAuthorsByBrowse/rankAuthorsList 口径）：仅浏览>0
 * 的作者入榜（0 浏览不占位，DOMAIN_RULES 空数据口径），按浏览数降序，行计数
 * = 累计浏览次数（Author.viewCount，格式对照原型 toLocaleString）。
 * 返回全量行，TOP 数量由调用方自控 slice。
 */
export function authorRankRows(authors: Author[]): RankRowData[] {
  return authors
    .filter((a) => (a.viewCount ?? 0) > 0)
    .slice()
    .sort((a, b) => (b.viewCount ?? 0) - (a.viewCount ?? 0))
    .map((a) => ({ name: authorDisplayName(a), count: (a.viewCount ?? 0).toLocaleString(LOCALE_ZH) }))
}
