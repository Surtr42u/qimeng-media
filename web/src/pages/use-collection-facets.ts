/**
 * CollectionPage（作者集合页）专用的 GET /assets/facets 局部 hook——筛选胶囊计数数据源。
 *
 * 为什么不复用 web/src/hooks/use-assets.ts 的 useAssetFacets：该共享文件正被
 * 并行任务修改（2026-09-05 作者链路修复的冲突规避，禁改），本页只从那里
 * import 列表 hook（useAssetsInfinite），facets 消费就地内聚在本文件。
 * 语义与共享版一致：排自身口径（openapi 该端点 description）——被渲染维
 * 自身的参数由调用方省略，其余维度选择全部生效。
 * 仅本页使用、键含完整参数（选择变化即重取），无跨处失效需求，故不进
 * lib/query-keys（卫生约束：字面量仅此一处）。
 */

import { useQuery } from '@tanstack/react-query'
import {
  getApiV1AssetsFacets,
  type AssetFacets,
  type MediaType,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { ASSETS_QUERY_KEY } from '@/lib/query-keys'

/** 本页 facets 入参（/assets/facets 参数的子集：作者固定 + 其余维当前选择） */
export interface CollectionFacetParams {
  authorId?: string
  mediaType?: MediaType
  character?: string
  work?: string
}

/** 作者集合页胶囊计数（排自身口径；enabled=false 用于作者实体未定位时防无效请求） */
export function useCollectionFacets(params: CollectionFacetParams, enabled = true) {
  return useQuery({
    queryKey: [...ASSETS_QUERY_KEY, 'facets', 'collection', params],
    queryFn: () => unwrapSdkResult(getApiV1AssetsFacets({ query: params })),
    select: (res): AssetFacets => ({
      partitions: res.partitions ?? [],
      authors: res.authors ?? [],
      characters: res.characters ?? [],
      types: res.types ?? [],
    }),
    enabled,
  })
}
