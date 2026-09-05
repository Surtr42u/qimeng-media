/**
 * 文件整理操作 hooks（B-1 文件管理增强；铁律 7：组件不直接调 API）。
 * 三操作全走既有端点（协议零改动）：
 * - 移动/重命名：POST /assets/{id}/move（targetDir 必填 + newName 可选，
 *   一个端点两用——改名=同目录+新名，移动=新目录+原名；服务端保证
 *   asset_id 与全部关联数据零改动，移动后自动重算出处/COS 富化）；
 * - 删除：DELETE /assets/{id} = 移入回收站（铁律 4：DELETE 语义永不物理删）；
 * - 新建子目录：POST /dirs（幂等，已存在视为成功）。
 * 失效口径：服务端 move/delete 后发 library.changed SSE，SseBridge 会失效
 * 同族根键；本地 onSuccess 再失效一次保证本标签页即时反馈（SSE 到达
 * 顺序无保证，双保险不冲突——TanStack invalidate 幂等）。
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1AssetsByAssetId,
  postApiV1AssetsByAssetIdMove,
  postApiV1Dirs,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import {
  ASSETS_QUERY_KEY,
  AUTHORS_QUERY_KEY,
  DIRS_QUERY_KEY,
  RECOMMENDATIONS_QUERY_KEY,
  SOURCES_QUERY_KEY,
  TRASH_QUERY_KEY,
} from '@/lib/query-keys'

/** 移动/重命名（一个端点两用）：newName 省略 = 保留原名只移动 */
export function useMoveAsset() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { assetId: string; targetDir: string; newName?: string }) =>
      unwrapSdkResult(
        postApiV1AssetsByAssetIdMove({
          path: { assetId: args.assetId },
          body: { targetDir: args.targetDir, newName: args.newName },
        }),
      ),
    onSuccess: () => {
      // 资产族（列表/详情/facets）+ 目录树；富化重算会改出处与 COS 作者映射
      qc.invalidateQueries({ queryKey: ASSETS_QUERY_KEY })
      qc.invalidateQueries({ queryKey: DIRS_QUERY_KEY })
      qc.invalidateQueries({ queryKey: SOURCES_QUERY_KEY })
      qc.invalidateQueries({ queryKey: AUTHORS_QUERY_KEY })
    },
  })
}

/** 删除 = 移入回收站（库行删除、文件进回收站目录，维护页回收站可恢复） */
export function useDeleteAsset() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (assetId: string) =>
      unwrapSdkResult(deleteApiV1AssetsByAssetId({ path: { assetId } })),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ASSETS_QUERY_KEY })
      qc.invalidateQueries({ queryKey: TRASH_QUERY_KEY })
      // 列表/推荐流的卡片集合少一项
      qc.invalidateQueries({ queryKey: RECOMMENDATIONS_QUERY_KEY })
      // 目录树 fileCount 同步失效（与 useMoveAsset 对齐，消除本地即时性窗口）
      qc.invalidateQueries({ queryKey: DIRS_QUERY_KEY })
    },
  })
}

/** 新建子目录（POST /dirs 幂等）：path = 库内完整相对路径（父目录拼接由调用方完成） */
export function useCreateDir() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { libraryId: string; path: string }) =>
      unwrapSdkResult(postApiV1Dirs({ body: args })),
    onSuccess: () => qc.invalidateQueries({ queryKey: DIRS_QUERY_KEY }),
  })
}
