/**
 * 资产移动/重命名 mutation（POST /api/v1/assets/{assetId}/move）。
 *
 * 【归属待定】src/hooks 为禁止改动目录，本模块暂放 pages/_shared；
 * 主 AI 集成时应归位 src/hooks/use-assets.ts 系列并更名 useAssetMove（此注释即待办标记）。
 * 已发现的 hooks 缺口：移动/重命名在 hooks 层尚无封装，本文件是归位前的临时替代。
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { postApiV1AssetsByAssetIdMove } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/** 提交参数：资产 ID + 协议 MoveRequest（targetDir 库内相对路径，newName 可选重命名） */
export interface AssetMoveInput {
  assetId: string
  targetDir: string
  newName?: string
}

/**
 * 移动/重命名资产。
 * 成功：失效 assets 列表与 asset-detail（详情页显示新路径）；409 TARGET_EXISTS 由调用方
 * 以 apiErrorText 转文案（README 分层：本 hook 只做数据，不弹 toast）。
 */
export function useAssetMove() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: AssetMoveInput) =>
      unwrapSdkResult(
        postApiV1AssetsByAssetIdMove({
          path: { assetId: input.assetId },
          body: { targetDir: input.targetDir, newName: input.newName },
        }),
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
      void queryClient.invalidateQueries({ queryKey: ['asset-detail'] })
    },
  })
}
