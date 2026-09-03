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
  type AssetSummary,
  type CountRange,
  type SizeRange,
} from '@/api/generated'
import type { MediaCardProps } from '@/components/media/MediaCard'
import { unwrapSdkResult } from '@/lib/api-client'
import { formatDuration, formatShortDate } from '@/lib/format'

export type MediaType = 'image' | 'animated_image' | 'video'
export type AssetSort =
  | 'default' | 'fileDate' | 'addedDate' | 'viewCount' | 'playCount' | 'sizeBytes' | 'name' | 'favoriteAt'

/** 资产列表查询参数（协议 GET /assets 的用户面子集；分页游标由无限滚动管理）。
 *  区间值域与协议枚举一致（CountRange/SizeRange），不自行再造字符串。 */
export interface AssetListParams {
  libraryId?: string
  mediaType?: MediaType
  source?: string
  character?: string
  sort?: AssetSort
  order?: 'asc' | 'desc'
  limit?: number
  q?: string
  authorId?: string
  tagIds?: string[]
  tagMode?: 'fuzzy' | 'exact'
  includeCos?: boolean
  favorite?: boolean
  liked?: boolean
  viewRange?: CountRange
  playRange?: CountRange
  sizeRange?: SizeRange
  dateFrom?: string
  dateTo?: string
  yearFrom?: number
  yearTo?: number
}

/** 推荐流（M3 十维算法，seed=0 稳定排序；首页卡片流数据源） */
export function useRecommendations(limit = 60) {
  return useQuery({
    queryKey: ['api/v1/recommendations', limit],
    queryFn: () => unwrapSdkResult(getApiV1Recommendations({ query: { limit } })),
  })
}

/** 资产列表无限滚动（游标分页；相册/搜索/集合页共用口径）。
 *  enabled=false 用于"无搜索词/未找到合集实体"时避免无效请求（q 为空仍要渲染空态）。 */
export function useAssetsInfinite(params: AssetListParams = {}, enabled = true) {
  return useInfiniteQuery({
    queryKey: ['api/v1/assets', params],
    queryFn: ({ pageParam }) =>
      unwrapSdkResult(getApiV1Assets({ query: { ...params, cursor: pageParam } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  })
}

/** 类型总数徽标（limit=1 只取 totalMatched；搜索页类型 tab 计数用，不带其他筛选） */
export function useAssetsTotal(mediaType?: MediaType) {
  return useQuery({
    queryKey: ['api/v1/assets/total', mediaType ?? 'all'],
    queryFn: () => unwrapSdkResult(getApiV1Assets({ query: { limit: 1, mediaType } })),
    select: (page) => page.totalMatched ?? 0,
  })
}

/** AssetSummary → MediaCardProps（卡片展示字段映射唯一入口，搜索/集合页共用）。
 *  映射口径照 HomePage/AlbumsPage：封面=thumbUrl、标题=fileName、时长=视频 durationMs
 *  （图片省略）、up=出处 source、date=modifiedAt 短日期。 */
export function assetToCard(a: AssetSummary): MediaCardProps {
  return {
    id: a.id,
    cover: a.thumbUrl ?? '',
    title: a.fileName ?? '',
    duration: a.durationMs ? formatDuration(a.durationMs) : undefined,
    up: a.source ?? undefined,
    date: formatShortDate(a.modifiedAt),
  }
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
