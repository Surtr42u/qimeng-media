/**
 * 作者 hooks（作者管理页/作者集合页/顶栏推荐词消费；铁律 7：组件不直接调 API）。
 * 数据源 GET /authors（全量列表，含 id/displayName/type/fileCount/followed/viewCount）；
 * 关注状态以 followed 字段为准（服务端持久化，不再内存假数据）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getApiV1Authors, putApiV1AuthorsByAuthorIdFollow } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export const AUTHORS_QUERY_KEY = ['api/v1/authors'] as const

/** 作者全量列表（含文件数/关注态/浏览数） */
export function useAuthors() {
  return useQuery({
    queryKey: AUTHORS_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1Authors()),
  })
}

/** 关注/取关切换（PUT /authors/{authorId}/follow）；成功后失效列表让 followed 刷新 */
export function useToggleFollow() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { authorId: string; follow: boolean }) =>
      unwrapSdkResult(
        putApiV1AuthorsByAuthorIdFollow({
          path: { authorId: args.authorId },
          body: { follow: args.follow },
        }),
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: AUTHORS_QUERY_KEY }),
  })
}
