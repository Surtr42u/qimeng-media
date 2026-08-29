/**
 * 推荐流 hooks（协议 GET /api/v1/recommendations）。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1Recommendations, type MediaType } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { stableObjectKey } from './shared'

/** 推荐参数：seed=0 稳定排序，>0 刷新打散；limit 建议 ≤ DEFAULT_PAGE_SIZE */
export interface RecommendationsParams {
  seed?: number
  limit?: number
  mediaType?: MediaType
}

/** 推荐资产（首页"推荐"Tab 数据源，返回数组） */
export function useRecommendations(params?: RecommendationsParams) {
  return useQuery({
    queryKey: ['recommendations', stableObjectKey(params)],
    queryFn: () => unwrapSdkResult(getApiV1Recommendations({ query: params })),
  })
}
