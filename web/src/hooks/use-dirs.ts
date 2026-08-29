/**
 * 目录树 hooks（相册按出处分组/整理页用：协议 GET/POST /api/v1/dirs）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getApiV1Dirs, postApiV1Dirs } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

/** 目录树（dir/ 子目录递归结构；libraryId 省略 = 默认库） */
export function useDirTree(libraryId?: string) {
  return useQuery({
    queryKey: ['dirs', libraryId ?? ''],
    queryFn: () => unwrapSdkResult(getApiV1Dirs({ query: { libraryId } })),
  })
}

/** 新建目录（POST /api/v1/dirs，body { path, libraryId }） */
export function useDirCreate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { path: string; libraryId?: string }) =>
      unwrapSdkResult(postApiV1Dirs({ body: input })),
    onSuccess: () => {
      // 目录变更影响全部库的树（只有库 id 维度命中不够，全部失效最简且安全）
      void queryClient.invalidateQueries({ queryKey: ['dirs'] })
    },
  })
}
