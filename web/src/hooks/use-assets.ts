/**
 * 资产浏览/详情/推荐/打点 hooks（首页/相册/详情页消费；铁律 7：组件不直接调 API）。
 * 数据源全部来自生成 SDK（协议唯一口径）：列表带签名缩略图 URL（thumbUrl），
 * 详情带签名原件直链（origUrl，"查看永远发原件"）。
 */

import { useInfiniteQuery, useMutation, useQuery } from '@tanstack/react-query'
import {
  getApiV1Assets,
  getApiV1AssetsByAssetId,
  getApiV1AssetsFacets,
  getApiV1Recommendations,
  getApiV1Sources,
  postApiV1EventsView,
  type AssetSummary,
  type CountRange,
  type SizeRange,
} from '@/api/generated'
// facets 相关类型本文件也要用，import（而非仅 re-export）才能进当前模块作用域
import type { AssetFacets, FacetBucket, Partition } from '@/api/generated'
export type { AssetFacets, FacetBucket, Partition }
import type { MediaCardProps } from '@/components/media/MediaCard'
import { unwrapSdkResult } from '@/lib/api-client'
import { formatCardUp, formatDuration, formatShortDate } from '@/lib/format'

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
  /** COS 作品名（migration 0008：COS 分区下「角色」维的筛选值） */
  work?: string
  /** 只要 COS 作者关联文件（分区胶囊「COS」语义；与 includeCos 同真时优先） */
  cosOnly?: boolean
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

/** AssetSummary → MediaCardProps（卡片展示字段映射唯一入口，搜索/集合页共用，
 *  首页/相册页也复用）。映射口径（原型 #4）：封面=thumbUrl、标题=fileName、
 *  时长=视频 durationMs（图片/动图省略，角标不渲染）、作者行=authorNames[0]
 *  （多作者「名 等N」，无作者回退 source）、date=modifiedAt 短日期。 */
export function assetToCard(a: AssetSummary): MediaCardProps {
  return {
    id: a.id,
    cover: a.thumbUrl ?? '',
    title: a.fileName ?? '',
    duration: a.mediaType === 'video' && a.durationMs ? formatDuration(a.durationMs) : undefined,
    up: formatCardUp(a.authorNames, a.source),
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

/** 出处分组计数（搜索页/集合页来源维度；null 名 = 前端兜底"其他"） */
export function useSources() {
  return useQuery({
    queryKey: ['api/v1/sources'],
    queryFn: () => unwrapSdkResult(getApiV1Sources()),
  })
}

/** 相册四维筛选候选（分区/作者/角色/类型；GET /assets/facets，排自身口径）。
 *  params 里只传"其他维度"的当前选择——被渲染维自身的参数由调用方省略，
 *  服务端对每个维都是排自身计数（openapi 该端点 description）。
 *  source 与 authorId 同属「作者」行（排自身时两者都不传）；character 与
 *  work 同属「角色」行（同理）。 */
export function useAssetFacets(
  params: {
    partition?: Partition
    mediaType?: MediaType
    character?: string
    authorId?: string
    work?: string
    source?: string
  } = {},
) {
  return useQuery({
    queryKey: ['api/v1/assets/facets', params],
    queryFn: () => unwrapSdkResult(getApiV1AssetsFacets({ query: params })),
    select: (res): AssetFacets => ({
      partitions: res.partitions ?? [],
      authors: res.authors ?? [],
      characters: res.characters ?? [],
      types: res.types ?? [],
    }),
  })
}

/** 作者/角色行的胶囊混合候选类型：kind 决定回传哪个筛选参数
 *  （source=GET /assets 的 source；author=authorId；character=character；
 *  work=work——同一胶囊栏混合两种候选时前端按 kind 分派）。 */
export type FacetOptionKind = NonNullable<FacetBucket['kind']>

/** FacetBucket → 值行胶囊结构（label 用服务端显示名，count 带 fileCount，
 *  kind 供作者/角色混合行分派筛选参数）。 */
export function facetToOptions(
  buckets: FacetBucket[],
): { label: string; value: string; count: number; kind?: FacetOptionKind }[] {
  return buckets.map((b) => ({ label: b.name, value: b.key, count: b.fileCount, kind: b.kind }))
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
