/** 统计榜行只格式化服务端已排序的窗口结果，数据页和榜单页共用。 */
import type { TopAuthorItem, TopTagItem } from '@/api/generated'

export type RankRowData = { name: string; count: string }
export function authorRankRows(authors: TopAuthorItem[]): RankRowData[] {
  return authors.map((author) => ({ name: author.displayName, count: `${author.views} 次` }))
}
export function tagRankRows(tags: TopTagItem[]): RankRowData[] {
  return tags.map((tag) => ({ name: tag.tag, count: `${tag.views} 次` }))
}
