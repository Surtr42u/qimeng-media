/**
 * 时间轴标签 hooks（视频定位点打标：协议 /api/v1/assets/{id}/timeline-tags）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1AssetsByAssetIdTimelineTagsByTagId,
  getApiV1AssetsByAssetIdTimelineTags,
  postApiV1AssetsByAssetIdTimelineTags,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/** 某资产的时间轴标签（按 timeMillis 升序由服务端返回） */
export function useTimelineTags(assetId: string) {
  return useQuery({
    queryKey: ['timeline-tags', assetId],
    queryFn: () =>
      unwrapSdkResult(getApiV1AssetsByAssetIdTimelineTags({ path: { assetId } })),
  })
}

/** 在视频时间点打标 */
export function useTimelineTagCreate(assetId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { timeMillis: number; name: string }) =>
      unwrapSdkResult(
        postApiV1AssetsByAssetIdTimelineTags({ path: { assetId }, body: input }),
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['timeline-tags', assetId] })
    },
  })
}

/** 删除时间轴标签 */
export function useTimelineTagDelete(assetId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (tagId: string) =>
      unwrapSdkResult(
        deleteApiV1AssetsByAssetIdTimelineTagsByTagId({ path: { assetId, tagId } }),
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['timeline-tags', assetId] })
    },
  })
}
