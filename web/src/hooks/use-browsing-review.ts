import { useQuery } from '@tanstack/react-query'
import { getApiV1StatsReview, type BrowsingReview } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { STATS_QUERY_KEY } from '@/lib/query-keys'

/** 与统计共用失效入口；窗口独立缓存，迟到响应不会覆盖当前窗口。 */
export function useBrowsingReview(range: BrowsingReview['range']) {
  return useQuery({
    queryKey: [...STATS_QUERY_KEY, 'review', range],
    queryFn: () => unwrapSdkResult(getApiV1StatsReview({ query: { range } })),
  })
}
