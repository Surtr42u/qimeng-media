/**
 * 观看历史 hook（我的页消费；铁律 7：组件不直接调 API）。
 * 数据源 GET /history：每资产最近一次 kind='open' 事件按时间倒序（每资产一条）、
 * cursor 分页；响应 HistoryItem = AssetSummary + lastViewedAt（Unix 毫秒）。
 * 排除已删资产与 COS（协议默认；includeCos=true 才包含，历史页不暴露该开关）。
 */

import { useInfiniteQuery } from '@tanstack/react-query'
import { getApiV1History } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { DEFAULT_PAGE_SIZE } from '@/lib/constants'
import { HISTORY_QUERY_KEY } from '@/lib/query-keys'

/** enabled=false 时挂起不发请求（我的页默认停在关注 tab，切到历史 tab 再拉，
 *  免去每次进页的三连发白请求；缓存命中时切换即时显示）。 */
export function useHistoryInfinite(limit = DEFAULT_PAGE_SIZE, enabled = true) {
  return useInfiniteQuery({
    queryKey: [...HISTORY_QUERY_KEY, limit],
    queryFn: ({ pageParam }) =>
      unwrapSdkResult(getApiV1History({ query: { cursor: pageParam, limit } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  })
}
