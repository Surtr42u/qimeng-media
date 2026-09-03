/**
 * 标签 hooks（搜索页标签池/顶栏推荐词消费；铁律 7：组件不直接调 API）。
 * 数据源 GET /tags（全量标签池，含 id/name/fileCount——规模可控，全量加载）；
 * POST /tags 新建后失效列表（服务端按 name 去重，重复创建由调用方先查同名避免）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getApiV1Tags, postApiV1Tags } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export const TAGS_QUERY_KEY = ['api/v1/tags'] as const

/** 标签池全量列表（名称/文件数） */
export function useTags() {
  return useQuery({
    queryKey: TAGS_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1Tags()),
  })
}

/** 新建标签（返回新 Tag 含 id，调用方据此自动选中）；成功后失效标签池 */
export function useCreateTag() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => unwrapSdkResult(postApiV1Tags({ body: { name } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: TAGS_QUERY_KEY }),
  })
}
