/**
 * 统计/排行 hooks（数据页/完整榜单页消费；铁律 7：组件不直接调 API）。
 * 口径来源：DOMAIN_RULES §2（排行 = 纯热度 view+play+like，非个性化）、
 * §5（统计口径 = ViewEvent 事件流聚合；趋势 range 窗口表见 §5 固定窗口）。
 */

import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { getApiV1Rankings, getApiV1StatsOverview, getApiV1StatsTrends } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { MAX_PAGE_SIZE } from '@/lib/constants'
import { lengthCursorNext } from '@/lib/pagination'
import { RANKINGS_QUERY_KEY, STATS_QUERY_KEY } from '@/lib/query-keys'
import type { MediaType } from './use-assets'

/** 趋势 range 枚举（协议 GET /stats/trends query.range；窗口口径见 DOMAIN_RULES §5 固定窗口表） */
export type TrendsRange = '7d' | 'day' | 'week' | 'month' | 'quarter' | 'year' | '90d' | 'all'

/** 排行 period 枚举（协议 GET /rankings query.period；quarter = 近 90 天） */
export type RankingPeriod = 'day' | 'week' | 'month' | 'quarter' | 'year' | 'all'

/** 总览（文件数/类型分布/容量/今日与累计浏览；animated_image 计入 imageCount） */
export function useStatsOverview() {
  return useQuery({
    queryKey: [...STATS_QUERY_KEY, 'overview'],
    queryFn: () => unwrapSdkResult(getApiV1StatsOverview()),
  })
}

/**
 * 趋势分桶（viewCount/playCount/seconds；mediaType 缺省 = 全部类型）。
 * 「全部」档按数据跨度动态选粒度且桶之和守恒（DOMAIN_RULES §5）。
 */
export function useTrends(range: TrendsRange, mediaType?: MediaType) {
  return useQuery({
    queryKey: [...STATS_QUERY_KEY, 'trends', range, mediaType ?? 'all'],
    queryFn: () => unwrapSdkResult(getApiV1StatsTrends({ query: { range, mediaType } })),
  })
}

/**
 * 热度排行（响应 AssetSummary[] 已填充 viewCount/playCount 供角标）。
 * limit 缺省取协议单页上限（MAX_PAGE_SIZE，同步责任见 constants.ts）。
 * offset 单页切片偏移（协议 GET /rankings query.offset，默认 0 向后兼容
 * ——数据页 Top5/完整榜单页整表一次拉取的既有调用点不传即行为不变，
 * offset=0 不上线路，请求与改前逐字节一致）。
 */
export function useRankings(period: RankingPeriod | undefined, limit?: number, offset = 0) {
  return useQuery({
    queryKey: [...RANKINGS_QUERY_KEY, period ?? 'all', limit ?? MAX_PAGE_SIZE, offset],
    queryFn: () =>
      unwrapSdkResult(getApiV1Rankings({ query: { period, limit, offset: offset || undefined } })),
  })
}

/**
 * 热度排行无限分页（首页排行榜 tab 触底增量加载；offset 为 pageParam）。
 * 与 useRankings 分立：数据页/榜单页消费整表数组、本 hook 消费分页流，
 * 两种返回形态不能共用一个钩子。
 * - reloadKey 进 queryKey：qm:refresh 收到后 +1 → 整条流重置回第一页重拉
 *   （「重置分页重拉」语义；排行榜是确定性排序，不需要 seed 打散）。
 * - period 变化即 queryKey 换档，分页天然重置，无需额外 state。
 * - hasNextPage 判据同推荐流，口径单源在 lib/pagination.ts（lengthCursorNext）。
 * - E2（首页加载/刷新过渡）：换键重取期间保留旧榜单占位（TanStack 官方
 *   keepPreviousData 模式，与 use-assets.ts 推荐流同款）——qm:refresh 换
 *   reloadKey 与 period 换档期间旧榜保留，消费方 isLoading 只在真首屏为 true，
 *   不得整页闪「加载中…」。
 */
export function useRankingsInfinite(
  period: RankingPeriod | undefined,
  limit: number,
  reloadKey = 0,
) {
  return useInfiniteQuery({
    queryKey: [...RANKINGS_QUERY_KEY, 'paged', period ?? 'all', limit, reloadKey],
    queryFn: ({ pageParam }) =>
      unwrapSdkResult(getApiV1Rankings({ query: { period, limit, offset: pageParam || undefined } })),
    initialPageParam: 0,
    getNextPageParam: lengthCursorNext(limit),
    placeholderData: keepPreviousData,
  })
}
