/**
 * 媒体库管理 hooks（文件管理页消费；铁律 7：组件不直接调 API）。
 * 语义约定：注册库 = 旧 App「添加媒体文件夹」（用户自测添加/删除库的入口）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1LibrariesByLibraryId,
  getApiV1Dirs,
  getApiV1Libraries,
  postApiV1Libraries,
  postApiV1LibrariesByLibraryIdScan,
  putApiV1LibrariesByLibraryIdEnabled,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { DIRS_QUERY_KEY, LIBRARIES_QUERY_KEY } from '@/lib/query-keys'
import { invalidateLibraryContent } from '@/lib/query-invalidation'
import { batchFailureReason } from '@/lib/batch'

/** 库列表（含文件计数与扫描态） */
export function useLibraries() {
  return useQuery({
    queryKey: LIBRARIES_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1Libraries()),
  })
}

/**
 * 目录树：GET /api/v1/dirs（libraryId 协议必填；enabled=false 时挂起不发请求）。
 * 从 LibraryManagePage 的 DirBrowser 原样搬入——消除全应用唯一一处组件内联
 * 数据获取（铁律 7 / ADR-0008：组件零请求代码，api-client 只许 hooks 消费）。
 */
export function useDirTree(libraryId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...DIRS_QUERY_KEY, libraryId],
    queryFn: () => unwrapSdkResult(getApiV1Dirs({ query: { libraryId } })),
    enabled,
  })
}

/** 注册与触发扫描是两步；第二步失败仍返回已注册事实，禁止让用户重复注册。 */
export function useRegisterAndScanLibrary() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: { name: string; rootPath: string; kind?: 'normal' | 'cos' }) => {
      const library = await unwrapSdkResult(postApiV1Libraries({ body }))
      if (!library.id) return { library, scanError: '注册响应缺少库 ID，请刷新列表后单独扫描' }
      try {
        await unwrapSdkResult(postApiV1LibrariesByLibraryIdScan({ path: { libraryId: library.id } }))
        return { library, scanError: null }
      } catch (error) {
        return { library, scanError: batchFailureReason(error) }
      }
    },
    onSuccess: () => invalidateLibraryContent(qc),
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
    onSuccess: () => invalidateLibraryContent(qc),
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
    onSuccess: () => invalidateLibraryContent(qc),
  })
}
