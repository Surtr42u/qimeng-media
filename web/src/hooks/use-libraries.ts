/**
 * 媒体库管理 hooks（文件管理页消费；铁律 7：组件不直接调 API）。
 * 语义约定：注册库 = 旧 App「添加媒体文件夹」（用户自测添加/删除库的入口）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1LibrariesByLibraryId,
  getApiV1Libraries,
  postApiV1Libraries,
  postApiV1LibrariesByLibraryIdScan,
  putApiV1LibrariesByLibraryIdEnabled,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export const LIBRARIES_QUERY_KEY = ['api/v1/libraries'] as const

/** 库列表（含文件计数与扫描态） */
export function useLibraries() {
  return useQuery({
    queryKey: LIBRARIES_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1Libraries()),
  })
}

/** 注册媒体库（name/rootPath 必填，kind 缺省 normal；成功后服务端自动扫描） */
export function useRegisterLibrary() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { name: string; rootPath: string; kind?: 'normal' | 'cos' }) =>
      unwrapSdkResult(postApiV1Libraries({ body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: LIBRARIES_QUERY_KEY }),
  })
}

/** 触发全量重扫（异步 202，进度走 SSE；这里失效列表让 scanState 轮到最新） */
export function useScanLibrary() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (libraryId: string) =>
      unwrapSdkResult(postApiV1LibrariesByLibraryIdScan({ path: { libraryId } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: LIBRARIES_QUERY_KEY }),
  })
}

/** 删除库：只移除索引（级联清该库资产），磁盘文件与事件流不动——二次确认在 UI 层 */
export function useDeleteLibrary() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (libraryId: string) =>
      unwrapSdkResult(deleteApiV1LibrariesByLibraryId({ path: { libraryId } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: LIBRARIES_QUERY_KEY }),
  })
}

/** 库启用/停用开关：停用仅隐藏浏览面，记录/事件流/统计全保留（migration 0007） */
export function useSetLibraryEnabled() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { libraryId: string; enabled: boolean }) =>
      unwrapSdkResult(
        putApiV1LibrariesByLibraryIdEnabled({
          path: { libraryId: args.libraryId },
          body: { enabled: args.enabled },
        }),
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: LIBRARIES_QUERY_KEY }),
  })
}
