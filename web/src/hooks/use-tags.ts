/**
 * 标签 hooks：列表 / 创建 / 删除 / 资产标签整体替换（协议 /api/v1/tags、/assets/{id}/tags）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1TagsByTagId,
  getApiV1Tags,
  postApiV1Tags,
  putApiV1AssetsByAssetIdTags,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/** 标签列表（服务端已按 name 序返回——协议保证，客户端不再排序，避免规则双写） */
export function useTags() {
  return useQuery({
    queryKey: ['tags'],
    queryFn: () => unwrapSdkResult(getApiV1Tags()),
  })
}

/** 创建标签 */
export function useTagCreate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => unwrapSdkResult(postApiV1Tags({ body: { name } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['tags'] })
    },
  })
}

/** 删除标签（服务端同步清理资产关联——标签筛选结果会变，列表也需失效） */
export function useTagDelete() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (tagId: string) =>
      unwrapSdkResult(deleteApiV1TagsByTagId({ path: { tagId } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['tags'] })
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
    },
  })
}

/** 整组替换资产标签（PUT /assets/{id}/tags，body tagIds） */
export function useAssetTagsReplace(assetId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (tagIds: string[]) =>
      unwrapSdkResult(putApiV1AssetsByAssetIdTags({ path: { assetId }, body: { tagIds } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['asset-detail', assetId] })
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
    },
  })
}
