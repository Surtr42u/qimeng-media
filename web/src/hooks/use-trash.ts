/**
 * 回收站 hooks（回收站页消费；铁律 7：组件不直接调 API）。
 * 语义：删除资产 = 移入回收站（铁律 4）；恢复冲突时服务端自动重命名。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1Trash,
  deleteApiV1TrashByTrashId,
  getApiV1Trash,
  postApiV1TrashByTrashIdRestore,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import {
  ASSETS_QUERY_KEY, DIRS_QUERY_KEY, RECOMMENDATIONS_QUERY_KEY, TRASH_QUERY_KEY,
} from '@/lib/query-keys'

/** 回收站列表（含原路径/删除时间/大小） */
export function useTrash() {
  return useQuery({
    queryKey: TRASH_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1Trash()),
  })
}

/** 恢复单个条目（冲突自动重命名） */
export function useRestoreTrash() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (trashId: string) =>
      unwrapSdkResult(postApiV1TrashByTrashIdRestore({ path: { trashId } })),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: TRASH_QUERY_KEY })
      // 审查清偿（E/F卷·P2-2）：恢复=资产回到列表/目录树计数/推荐流，此前仅
      // TRASH 一键 + SSE library.changed 兜底——SSE 断连退避窗（1~30s）内跨页
      // 不一致；补齐与 useDeleteAsset 对称的失效面（F6 批量恢复放大了暴露面）
      qc.invalidateQueries({ queryKey: ASSETS_QUERY_KEY })
      qc.invalidateQueries({ queryKey: DIRS_QUERY_KEY })
      qc.invalidateQueries({ queryKey: RECOMMENDATIONS_QUERY_KEY })
    },
  })
}

/** 彻底删除单个条目（物理删除，二次确认在 UI 层）。仅回收站视图变化——
 *  资产族/目录树在移入回收站（useDeleteAsset）时已失效，物理删除不再触及 */
export function useDeleteTrashItem() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (trashId: string) =>
      unwrapSdkResult(deleteApiV1TrashByTrashId({ path: { trashId } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: TRASH_QUERY_KEY }),
  })
}

/** 清空回收站（物理删除全部，二次确认在 UI 层）；失效面口径同上 */
export function useEmptyTrash() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => unwrapSdkResult(deleteApiV1Trash()),
    onSuccess: () => qc.invalidateQueries({ queryKey: TRASH_QUERY_KEY }),
  })
}
