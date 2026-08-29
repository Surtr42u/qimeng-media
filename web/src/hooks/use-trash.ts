/**
 * 回收站 hooks（协议 DELETE/get /api/v1/trash）。
 * DELETE 语义 = 移入回收站；本文件是"物理删除/恢复"的管理操作（ADAPTER 红线：走回收站而非直删）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1Trash,
  deleteApiV1TrashByTrashId,
  getApiV1Trash,
  postApiV1TrashByTrashIdRestore,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/** 回收站列表（按删除时间倒序由服务端返回） */
export function useTrashItems() {
  return useQuery({
    queryKey: ['trash'],
    queryFn: () => unwrapSdkResult(getApiV1Trash()),
  })
}

/** 单条恢复（冲突时服务端自动重命名） */
export function useTrashRestore() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (trashId: string) =>
      unwrapSdkResult(postApiV1TrashByTrashIdRestore({ path: { trashId } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['trash'] })
      // 恢复的资产回到库/列表，让列表失效刷新
      void queryClient.invalidateQueries({ queryKey: ['assets'] })
    },
  })
}

/** 单条物理删除（不可逆；调用前需二次确认文案由页面负责） */
export function useTrashDelete() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (trashId: string) =>
      unwrapSdkResult(deleteApiV1TrashByTrashId({ path: { trashId } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['trash'] })
    },
  })
}

/** 清空回收站（不可逆；调用前需二次确认） */
export function useTrashClear() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => unwrapSdkResult(deleteApiV1Trash()),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['trash'] })
    },
  })
}
