/**
 * 资产浏览/详情/推荐/打点 hooks（首页/相册/详情页消费；铁律 7：组件不直接调 API）。
 * 数据源全部来自生成 SDK（协议唯一口径）：列表带签名缩略图 URL（thumbUrl），
 * 详情带签名原件直链（origUrl，"查看永远发原件"）。
 */

import { useInfiniteQuery, useMutation, useQuery } from '@tanstack/react-query'
import {
  getApiV1Assets,
  getApiV1AssetsByAssetId,
  getApiV1Recommendations,
  getApiV1Sources,
  postApiV1EventsView,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export type MediaType = 'image' | 'animated_image' | 'video'
export type AssetSort = 'default' | 'fileDate' | 'addedDate' | 'viewCount' | 'playCount' | 'sizeBytes' | 'name'

/** 资产列表查询参数（协议 GET /assets 的用户面子集；分页游标由无限滚动管理） */
export interface AssetListParams {
  libraryId?: string
  mediaType?: MediaType
  source?: string
  character?: string
  sort?: AssetSort
  order?: 'asc' | 'desc'
  limit?: number
  q?: string
}

/** 推荐流（M3 十维算法，seed=0 稳定排序；首页卡片流数据源） */
export function useRecommendations(limit = 60) {
  return useQuery({
    queryKey: ['api/v1/recommendations', limit],
    queryFn: () => unwrapSdkResult(getApiV1Recommendations({ query: { limit } })),
  })
}

/** 资产列表无限滚动（游标分页；相册/搜索共用口径） */
export function useAssetsInfinite(params: AssetListParams = {}) {
  return useInfiniteQuery({
    queryKey: ['api/v1/assets', params],
    queryFn: ({ pageParam }) =>
      unwrapSdkResult(getApiV1Assets({ query: { ...params, cursor: pageParam } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
}

/** 资产详情（签名原件直链/编码信息/标签作者——详情页与播放兼容性判断数据源） */
export function useAssetDetail(assetId?: string) {
  return useQuery({
    queryKey: ['api/v1/assets/detail', assetId],
    queryFn: () => unwrapSdkResult(getApiV1AssetsByAssetId({ path: { assetId: assetId! } })),
    enabled: !!assetId,
  })
}

/** 出处分组计数（相册页「分区」维度值行；null 名 = 前端兜底"其他"） */
export function useSources() {
  return useQuery({
    queryKey: ['api/v1/sources'],
    queryFn: () => unwrapSdkResult(getApiV1Sources()),
  })
}

/** 行为上报（DOMAIN_RULES §5：open/play/dwell；会话去重依赖每标签页 sessionId） */
export function useReportView() {
  return useMutation({
    mutationFn: (body: {
      assetId: string
      kind: 'open' | 'play' | 'dwell'
      startedAt: string
      sessionId: string
      seconds?: number
    }) => unwrapSdkResult(postApiV1EventsView({ body })),
  })
}
