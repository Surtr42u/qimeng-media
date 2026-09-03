/**
 * 统计/排行 hooks（数据页/完整榜单页消费；铁律 7：组件不直接调 API）。
 * 口径来源：DOMAIN_RULES §2（排行 = 纯热度 view+play+like，非个性化）、
 * §5（统计口径 = ViewEvent 事件流聚合；趋势 range 窗口表见 §5 固定窗口）。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1Rankings, getApiV1StatsOverview, getApiV1StatsTrends } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { MAX_PAGE_SIZE } from '@/lib/constants'
import type { MediaType } from './use-assets'

/** 趋势 range 枚举（协议 GET /stats/trends query.range；窗口口径见 DOMAIN_RULES §5 固定窗口表） */
export type TrendsRange = '7d' | 'day' | 'week' | 'month' | 'quarter' | 'year' | '90d' | 'all'

/** 排行 period 枚举（协议 GET /rankings query.period；quarter = 近 90 天） */
export type RankingPeriod = 'day' | 'week' | 'month' | 'quarter' | 'year' | 'all'

/** 总览（文件数/类型分布/容量/今日与累计浏览；animated_image 计入 imageCount） */
export function useStatsOverview() {
  return useQuery({
    queryKey: ['api/v1/stats/overview'],
    queryFn: () => unwrapSdkResult(getApiV1StatsOverview()),
  })
}

/**
 * 趋势分桶（viewCount/playCount/seconds；mediaType 缺省 = 全部类型）。
 * 「全部」档按数据跨度动态选粒度且桶之和守恒（DOMAIN_RULES §5）。
 */
export function useTrends(range: TrendsRange, mediaType?: MediaType) {
  return useQuery({
    queryKey: ['api/v1/stats/trends', range, mediaType ?? 'all'],
    queryFn: () => unwrapSdkResult(getApiV1StatsTrends({ query: { range, mediaType } })),
  })
}

/**
 * 热度排行（响应 AssetSummary[] 已填充 viewCount/playCount 供角标）。
 * limit 缺省取协议单页上限（MAX_PAGE_SIZE，同步责任见 constants.ts）。
 */
export function useRankings(period: RankingPeriod | undefined, limit?: number) {
  return useQuery({
    queryKey: ['api/v1/rankings', period ?? 'all', limit ?? MAX_PAGE_SIZE],
    queryFn: () => unwrapSdkResult(getApiV1Rankings({ query: { period, limit } })),
  })
}
