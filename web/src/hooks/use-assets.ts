/**
 * 资产列表 hooks：useInfiniteQuery 核心（协议 GET /api/v1/assets，cursor 分页）。
 *
 * 筛选参数与协议 query 一一对应（类型直接 Omit 生成类型，协议变更自动对齐）；
 * 页面只传筛选意图，分页（limit/cursor）由本层接管。
 */

import { useInfiniteQuery } from '@tanstack/react-query'
import { getApiV1Assets, type GetApiV1AssetsData } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { DEFAULT_PAGE_SIZE } from '@/lib/constants'
import { stableObjectKey } from './shared'

/** 资产筛选参数：协议 /api/v1/assets query 去掉游标后的全部字段 */
export type AssetFilters = Omit<NonNullable<GetApiV1AssetsData['query']>, 'cursor'>

/** 列表数据 key（收藏/点赞/上传/标签变更 invalidate 时用 ['assets'] 即可命中全部筛选组合） */
export function assetsListKey(filters?: AssetFilters): readonly unknown[] {
  return ['assets', stableObjectKey(filters)]
}

/**
 * 资产列表（无限滚动支持）。
 * @param filters 筛选参数（见 AssetFilters）；undefined = 全部资产
 * @returns data = { items: AssetSummary[]（已摊平全部页）, totalMatched: number }
 *   其余字段继承 useInfiniteQuery（fetchNextPage/hasNextPage/isFetchingNextPage...）
 */
export function useAssets(filters?: AssetFilters) {
  return useInfiniteQuery({
    queryKey: assetsListKey(filters),
    queryFn: ({ pageParam }) =>
      unwrapSdkResult(
        getApiV1Assets({
          query: {
            ...filters,
            // 协议默认 limit=60（server/internal/httpapi/pagination.go defaultPageLimit，
            // openapi limit default）——此处显式传，避免服务端默认值漂移影响前端行为
            limit: DEFAULT_PAGE_SIZE,
            cursor: pageParam,
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    // 服务端以 nextCursor 返回下一页游标；null = 没有更多（hasNextPage 自动推算）
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    // select 摊平多页：页面只消费 items 数组，不关心分页内部结构
    select: (data) => ({
      items: data.pages.flatMap((page) => page.items ?? []),
      totalMatched: data.pages[0]?.totalMatched ?? 0,
    }),
  })
}
