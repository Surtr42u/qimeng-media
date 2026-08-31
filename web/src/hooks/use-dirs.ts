/**
 * 目录树 hooks（相册按出处分组/整理页用：协议 GET/POST /api/v1/dirs）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getApiV1Dirs, postApiV1Dirs } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/** 目录树（dir/ 子目录递归结构；libraryId 协议必填，未传入时查询禁用——由调用方先选定库） */
export function useDirTree(libraryId?: string) {
  return useQuery({
    queryKey: ['dirs', libraryId ?? ''],
    queryFn: () => {
      if (!libraryId) {
        // enabled 保护下不可达；防御性显式错误（协议必填参数缺失属于调用方编程错误）
        throw new Error('dirs: libraryId 必填（协议必填参数）')
      }
      return unwrapSdkResult(getApiV1Dirs({ query: { libraryId } }))
    },
    enabled: !!libraryId,
  })
}

/** 新建目录（POST /api/v1/dirs，body { path, libraryId }——libraryId 协议必填） */
export function useDirCreate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { path: string; libraryId: string }) =>
      unwrapSdkResult(postApiV1Dirs({ body: input })),
    onSuccess: () => {
      // 目录变更影响全部库的树（只有库 id 维度命中不够，全部失效最简且安全）
      void queryClient.invalidateQueries({ queryKey: ['dirs'] })
    },
  })
}
