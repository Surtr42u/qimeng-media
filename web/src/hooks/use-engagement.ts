/**
 * 互动 hooks：点赞（toggle）与收藏（显式开/关）。
 *
 * 两个端点语义不同（协议注释）：
 * - like：PUT /assets/{id}/like，服务端 toggle，返回 LikeState（likedToday/likeCount）
 * - favorite：PUT /assets/{id}/favorite，body 显式 { favorite: boolean }
 */

import { useCallback, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  putApiV1AssetsByAssetIdFavorite,
  putApiV1AssetsByAssetIdLike,
  type AssetDetail,
  type LikeState,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/**
 * 点赞 toggle。
 * 为什么用本地 state 而非写详情缓存：AssetDetail 不含 likedToday 字段（只有 likeCount），
 * 缓存里没有"今天是否赞过"的基线，无法从缓存直接推翻转方向——
 * 所以乐观更新只在"本地已知基线"时生效：首次点赞由服务端定夺（返回 LikeState 回填），
 * 后续点击在本地基线基础上立即翻转，连续点击有即时反馈。
 * 服务端确认后 invalidate 列表（likeCount 列变）+ 回填本地状态。
 */
export function useLike(assetId: string) {
  const queryClient = useQueryClient()
  const [likeState, setLikeState] = useState<LikeState | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      unwrapSdkResult(putApiV1AssetsByAssetIdLike({ path: { assetId } })),
    onSuccess: (state) => setLikeState(state),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
      void queryClient.invalidateQueries({ queryKey: ['asset-detail', assetId] })
    },
  })

  const toggle = useCallback(() => {
    // 已有本地基线 → 先乐观翻转；无基线 → 交由服务端决定方向
    setLikeState((prev) =>
      prev
        ? {
            likedToday: !prev.likedToday,
            // likeCount 是可选字段（LikeState.likeCount?），取 0 起步；下界 0 防服务端异常负值
            likeCount: Math.max(0, (prev.likeCount ?? 0) + (prev.likedToday ? -1 : 1)),
          }
        : prev,
    )
    mutation.mutate()
  }, [mutation])

  return {
    likedToday: likeState?.likedToday ?? false,
    likeCount: likeState?.likeCount ?? 0,
    toggle,
    isPending: mutation.isPending,
  }
}

/**
 * 收藏显式设置（body.favorite）：乐观更新详情缓存（isFavorite 在 AssetSummary/Detail 上都有），
 * 失败回滚；settled 后 invalidate 列表（收藏筛选/角标随行）。
 */
export function useFavorite(assetId: string) {
  const queryClient = useQueryClient()
  const detailKey = ['asset-detail', assetId]

  return useMutation({
    mutationFn: (favorite: boolean) =>
      unwrapSdkResult(
        putApiV1AssetsByAssetIdFavorite({ path: { assetId }, body: { favorite } }),
      ),
    onMutate: async (favorite) => {
      await queryClient.cancelQueries({ queryKey: detailKey })
      const previous = queryClient.getQueryData<AssetDetail>(detailKey)
      queryClient.setQueryData<AssetDetail>(detailKey, (old) =>
        old ? { ...old, isFavorite: favorite } : old,
      )
      return { previous }
    },
    onError: (_error, _favorite, context) => {
      // 回滚乐观值（context.previous 是 mutationFn 入参外的自定义返回）
      if (context?.previous) queryClient.setQueryData(detailKey, context.previous)
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: detailKey })
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
    },
  })
}
