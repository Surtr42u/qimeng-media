/**
 * 作者 hooks（作者管理页/作者集合页/顶栏推荐词/文件管理 TXT 卡消费；
 * 铁律 7：组件不直接调 API）。
 * 数据源 GET /authors（全量列表，含 id/displayName/type/fileCount/followed/viewCount）；
 * 关注状态以 followed 字段为准（服务端持久化，不再内存假数据）。
 * TXT 导入作者（旧版数据管理「TXT导入作者」卡）：POST 导入=统一重建语义
 * （跨 TXT 同名作者关联并集），GET 列出已导入文件名，DELETE 移除单个片段并
 * 从剩余片段重建——删除/导入都会改变作者表与关联，需失效作者列表。
 * 上传挂靠族（REQ-上传指定作者与来源 §3.1）：联想/来源词表供上传卡两个
 * 挂靠字段消费；导出/镜像分别供 TXT 导入卡与设置页镜像卡消费。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1AuthorsImportTxt,
  getApiV1Authors,
  getApiV1AuthorsImportTxt,
  getApiV1AuthorsImportTxtExport,
  getApiV1AuthorsMirror,
  getApiV1AuthorsSources,
  getApiV1AuthorsSuggest,
  postApiV1AuthorsImportTxt,
  postApiV1AuthorsImportTxtRebuild,
  putApiV1AuthorsByAuthorIdFollow,
  putApiV1AuthorsMirror,
  type AuthorMirrorConfig,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { downloadBlob } from '@/lib/download'
import {
  AUTHOR_MIRROR_QUERY_KEY,
  AUTHOR_SOURCES_QUERY_KEY,
  AUTHOR_SUGGEST_QUERY_KEY,
  AUTHORS_QUERY_KEY,
  TXT_FILES_QUERY_KEY,
} from '@/lib/query-keys'

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
 *  conflictResolution：同文件名重导入且新内容缺少上传写入条目时，服务端先回
 *  409 TXT_CONFLICT（载荷 TxtImportConflict）由用户二选一——keep=自动并回上传
 *  条目后替换落库，remove=按用户明示移除（REQ §3.3 第 10 条）；缺省不传。
 *  onSuccess 由调用方注入（toast/清空输入/清冲突态）；内部失效文件名列表与作者列表。 */
export function useImportAuthorTxt() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: {
      filename: string
      content: string
      conflictResolution?: 'keep' | 'remove'
    }) =>
      unwrapSdkResult(
        postApiV1AuthorsImportTxt({
          body: {
            filename: args.filename,
            content: args.content,
            conflictResolution: args.conflictResolution,
          },
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

/* ===== 上传挂靠（REQ-上传指定作者与来源 §3.1）：联想/词表/导出/镜像 ===== */

/** 作者联想条数上限（与 openapi /authors/suggest limit default=10 双写——
 *  协议侧改动须同步此处，反之亦然；定位 api/openapi.yaml 该端点 limit 参数） */
const AUTHOR_SUGGEST_LIMIT = 10

/** 作者来源词表缓存时效：词表随作者数据缓慢扩充（上传并入新来源才变），
 *  5 分钟内重复打开上传卡不重发请求；上传成功后由调用方按需失效 */
const AUTHOR_SOURCES_STALE_MS = 5 * 60 * 1000

/** 导出片段的 MIME（协议 200 响应 text/plain；charset 显式 utf-8 保证含
 *  中文作者名的片段往返一致） */
const EXPORT_TXT_MIME = 'text/plain;charset=utf-8'

/** 匿名片段导出文件名（与服务端 exportAnonymousName 口径一致，互指：
 *  单一来源在 api/openapi.yaml /authors/import-txt/export 200 description
 *  「匿名片段用『作者清单.txt』」——改名须两侧同步） */
const ANONYMOUS_EXPORT_FILENAME = '作者清单.txt'

/** 作者联想（GET /authors/suggest；q trim 后非空才发请求；键用 trim 后的词，
 *  同 useSearchSuggestions 口径）。子串大小写不敏感命中由服务端负责，前端只展示。 */
export function useAuthorSuggest(q: string, enabled = true) {
  const trimmed = q.trim()
  return useQuery({
    queryKey: [...AUTHOR_SUGGEST_QUERY_KEY, trimmed],
    queryFn: () =>
      unwrapSdkResult(
        getApiV1AuthorsSuggest({ query: { q: trimmed, limit: AUTHOR_SUGGEST_LIMIT } }),
      ),
    enabled: enabled && trimmed !== '',
  })
}

/** 作者来源词表（GET /authors/sources；常用优先=服务端已按 authorCount 降序，
 *  前端不再排。与 §4 资产出处分区词表互不相干，禁止混用）。 */
export function useAuthorSources() {
  return useQuery({
    queryKey: AUTHOR_SOURCES_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1AuthorsSources()),
    staleTime: AUTHOR_SOURCES_STALE_MS,
  })
}

/** 作者总表镜像配置读取（GET /authors/mirror；path 空=关闭） */
export function useAuthorMirror() {
  return useQuery({
    queryKey: AUTHOR_MIRROR_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1AuthorsMirror()),
  })
}

/** 保存镜像配置（PUT /authors/mirror；保存成功即尝试一次镜像刷新）；成功后失效同 key */
export function useSaveAuthorMirror() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (cfg: AuthorMirrorConfig) => unwrapSdkResult(putApiV1AuthorsMirror({ body: cfg })),
    onSuccess: () => qc.invalidateQueries({ queryKey: AUTHOR_MIRROR_QUERY_KEY }),
  })
}

/** 导出片段原文并触发浏览器下载（GET /authors/import-txt/export）。
 *  filename 空串 = 匿名导入的最近一份；下载文件名 = 片段名，匿名片段用
 *  ANONYMOUS_EXPORT_FILENAME（与服务端 Content-Disposition 同口径）。 */
export function useExportTxtSource() {
  return useMutation({
    mutationFn: async (filename: string) => {
      const text = await unwrapSdkResult(
        getApiV1AuthorsImportTxtExport({ query: { filename } }),
      )
      downloadBlob(
        new Blob([text], { type: EXPORT_TXT_MIME }),
        filename || ANONYMOUS_EXPORT_FILENAME,
      )
    },
  })
}
