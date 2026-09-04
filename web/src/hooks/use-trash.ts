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
import { TRASH_QUERY_KEY } from '@/lib/query-keys'

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
    onSuccess: () => qc.invalidateQueries({ queryKey: TRASH_QUERY_KEY }),
  })
}

/** 彻底删除单个条目（物理删除，二次确认在 UI 层） */
export function useDeleteTrashItem() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (trashId: string) =>
      unwrapSdkResult(deleteApiV1TrashByTrashId({ path: { trashId } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: TRASH_QUERY_KEY }),
  })
}

/** 清空回收站（物理删除全部，二次确认在 UI 层） */
export function useEmptyTrash() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => unwrapSdkResult(deleteApiV1Trash()),
    onSuccess: () => qc.invalidateQueries({ queryKey: TRASH_QUERY_KEY }),
  })
}
