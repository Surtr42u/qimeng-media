import type { StatsOverview, TrendBucket } from '@/api/generated'

// 与 OpenAPI /stats/top-authors、top-tags limit.maximum 双向同步。
export const STATS_RANK_LIMIT = 50
export const STATS_LOAD_FAILED_MESSAGE = '统计加载失败，请检查连接后重试'
export const STATS_PROTOCOL_MESSAGE = '服务端统计协议较旧，请同步更新服务端'

/** 分板块反馈仍保留旧协议提示，不能将兼容问题伪装成真实空统计。 */
export function statsReadMessage(error: Error | null, section: string): string {
  return error?.message === STATS_PROTOCOL_MESSAGE ? STATS_PROTOCOL_MESSAGE : `${section}读取失败，请检查连接后重试`
}
export const STATS_MEDIA_TYPES = ['image', 'video', 'animated_image'] as const
export const STATS_SOURCES = ['normal', 'cos'] as const
export const STATS_SEGMENTS = [
  { label: '近 7 天', note: '近 7 天 · 逐日', trends: '7d', period: 'week' },
  { label: '近 30 天', note: '近 30 天 · 逐日', trends: 'day', period: 'month' },
  { label: '近 90 天', note: '近 90 天 · 逐日', trends: '90d', period: 'quarter' },
  { label: '全部', note: '全部 · 动态分桶', trends: 'all', period: 'all' },
] as const

const OVERVIEW_FIELDS = ['stillImageCount', 'animatedImageCount', 'viewedFilesCount',
  'viewedAuthorsCount', 'viewedTagsCount', 'windowTotals', 'mediaTypeWindowTotals', 'sourceWindowTotals'] as const
export type CompleteStatsOverview = StatsOverview & Required<Pick<StatsOverview, typeof OVERVIEW_FIELDS[number]>>

/** 缺新字段必须明确报兼容错误，不能把未知数据伪装成 0。 */
export function requireStatsOverview(value: StatsOverview): CompleteStatsOverview {
  if (OVERVIEW_FIELDS.some((key) => value[key] == null) ||
      STATS_MEDIA_TYPES.some((key) => !value.mediaTypeWindowTotals?.[key]) ||
      STATS_SOURCES.some((key) => !value.sourceWindowTotals?.[key])) throw new Error(STATS_PROTOCOL_MESSAGE)
  return value as CompleteStatsOverview
}

export function requireStatsTrends(points: TrendBucket[]): TrendBucket[] {
  if (points.some((point) => !point.start || !point.end ||
    STATS_MEDIA_TYPES.some((key) => !point.mediaTypes?.[key]) ||
    STATS_SOURCES.some((key) => !point.sources?.[key]))) throw new Error(STATS_PROTOCOL_MESSAGE)
  return points
}
