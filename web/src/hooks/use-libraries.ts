/**
 * 媒体库 hooks：列表 / 新建 / 触发扫描。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1Libraries,
  postApiV1Libraries,
  postApiV1LibrariesByLibraryIdScan,
  type LibraryCreate,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/** 库列表查询 key（SseBridge 与上传流程同样用它 invalidate） */
export const librariesQueryKey = ['libraries'] as const

/** 所有媒体库（协议 GET /api/v1/libraries） */
export function useLibraries() {
  return useQuery({
    queryKey: librariesQueryKey,
    queryFn: () => unwrapSdkResult(getApiV1Libraries()),
  })
}

/** 新建媒体库（POST /api/v1/libraries） */
export function useLibrariesCreate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: LibraryCreate) => unwrapSdkResult(postApiV1Libraries({ body: input })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: librariesQueryKey })
    },
  })
}

/**
 * 触发全量扫描（POST /api/v1/libraries/{id}/scan，202）。
 * 扫描进度由 SSE scan.progress 推送（management 页自己订阅），
 * 这里 invalidate 是为了刷新库的 scanState（idle→scanning），
 * 库内容变化则由 SseBridge 统一刷新。
 */
export function useLibrariesScan() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (libraryId: string) =>
      unwrapSdkResult(postApiV1LibrariesByLibraryIdScan({ path: { libraryId } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: librariesQueryKey })
    },
  })
}
