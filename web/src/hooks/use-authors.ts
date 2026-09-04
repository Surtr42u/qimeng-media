/**
 * 作者 hooks（作者管理页/作者集合页/顶栏推荐词/文件管理 TXT 卡消费；
 * 铁律 7：组件不直接调 API）。
 * 数据源 GET /authors（全量列表，含 id/displayName/type/fileCount/followed/viewCount）；
 * 关注状态以 followed 字段为准（服务端持久化，不再内存假数据）。
 * TXT 导入作者（旧版数据管理「TXT导入作者」卡）：POST 导入=统一重建语义
 * （跨 TXT 同名作者关联并集），GET 列出已导入文件名，DELETE 移除单个片段并
 * 从剩余片段重建——删除/导入都会改变作者表与关联，需失效作者列表。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1AuthorsImportTxt,
  getApiV1Authors,
  getApiV1AuthorsImportTxt,
  postApiV1AuthorsImportTxt,
  postApiV1AuthorsImportTxtRebuild,
  putApiV1AuthorsByAuthorIdFollow,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { AUTHORS_QUERY_KEY, TXT_FILES_QUERY_KEY } from '@/lib/query-keys'

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

/** 已导入 TXT 文件名列表（文件名升序；GET /authors/import-txt） */
export function useTxtImportedFiles() {
  return useQuery({
    queryKey: TXT_FILES_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1AuthorsImportTxt()),
  })
}

/** 导入作者 TXT（三格式自动识别 + 统一重建；POST /authors/import-txt）。
 *  onSuccess 由调用方注入（toast/清空输入）；内部失效文件名列表与作者列表。 */
export function useImportAuthorTxt() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { filename: string; content: string }) =>
      unwrapSdkResult(
        postApiV1AuthorsImportTxt({
          body: { filename: args.filename, content: args.content },
        }),
      ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: TXT_FILES_QUERY_KEY })
      qc.invalidateQueries({ queryKey: AUTHORS_QUERY_KEY })
    },
  })
}

/** 重放全部已导入 TXT 片段重建常规作者-文件关联（POST /authors/import-txt/rebuild；
 *  幂等修复入口：不新增/修改片段，库重建/关联丢失后重放恢复；无片段返回零值）。
 *  onSuccess 由调用方注入（toast）；重放只改关联，失效作者列表。 */
export function useRebuildAuthorTxt() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => unwrapSdkResult(postApiV1AuthorsImportTxtRebuild()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: AUTHORS_QUERY_KEY })
    },
  })
}

/** 移除一个已导入 TXT 片段并从剩余片段统一重建（DELETE /authors/import-txt）。
 *  404（片段不存在）抛给调用方 toast。 */
export function useDeleteImportedTxt() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (filename: string) =>
      unwrapSdkResult(deleteApiV1AuthorsImportTxt({ query: { filename } })),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: TXT_FILES_QUERY_KEY })
      qc.invalidateQueries({ queryKey: AUTHORS_QUERY_KEY })
    },
  })
}
