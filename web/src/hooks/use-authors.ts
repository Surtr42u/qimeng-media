/**
 * 作者 hooks（作者管理页/作者集合页/顶栏推荐词/文件管理 TXT 卡消费；
 * 铁律 7：组件不直接调 API）。
 * 数据源 GET /authors（全量列表，含 id/displayName/type/fileCount/followed/viewCount）；
 * 关注状态以 followed 字段为准（服务端持久化，不再内存假数据）。
 * TXT 导入作者（旧版数据管理「TXT导入作者」卡）：POST 导入=统一重建语义
 * （跨 TXT 同名作者关联并集），GET 列出已导入文件名，DELETE 移除单个片段并
 * 从剩余片段重建——删除/导入都会改变作者表与关联，需失效作者列表。
 * 来源词表族（2026-09-25 协议批）：通用来源词表（GET/PUT
 * /authors/source-vocabulary，服务端手动维护的小清单）+ 单作者来源区
 * （GET/PUT /authors/{authorId}/sources，个人片段词）——设置页词表卡与
 * 资产编辑页来源区消费；导出/镜像分别供 TXT 导入卡与设置页镜像卡消费。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1AuthorsImportTxt,
  getApiV1Authors,
  getApiV1AuthorsByAuthorIdSources,
  getApiV1AuthorsImportTxt,
  getApiV1AuthorsImportTxtExport,
  getApiV1AuthorsMirror,
  getApiV1AuthorsSourceVocabulary,
  getApiV1AuthorsSuggest,
  postApiV1AuthorsImportTxt,
  postApiV1AuthorsImportTxtRebuild,
  putApiV1AssetsByAssetIdAuthors,
  putApiV1AuthorsByAuthorIdFollow,
  putApiV1AuthorsByAuthorIdSources,
  putApiV1AuthorsMirror,
  putApiV1AuthorsSourceVocabulary,
  type AuthorMirrorConfig,
  type SourceVocabulary,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { downloadBlob } from '@/lib/download'
import {
  ASSETS_QUERY_KEY,
  AUTHOR_MIRROR_QUERY_KEY,
  AUTHOR_SOURCES_BY_ID_QUERY_KEY,
  AUTHOR_SUGGEST_QUERY_KEY,
  AUTHORS_QUERY_KEY,
  SOURCE_VOCABULARY_QUERY_KEY,
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

/* ===== 来源词表族（2026-09-25 协议批）：通用词表/单作者来源区/联想/导出/镜像 ===== */

/** 作者联想条数上限（与 openapi /authors/suggest limit default=10 双写——
 *  协议侧改动须同步此处，反之亦然；定位 api/openapi.yaml 该端点 limit 参数） */
const AUTHOR_SUGGEST_LIMIT = 10

/** 来源词表缓存时效：通用词表是服务端手动维护的小清单、单作者来源区随
 *  编辑低频变化，5 分钟内重复打开不重发请求；保存后由各自 mutation 按键失效 */
const SOURCE_VOCABULARY_STALE_MS = 5 * 60 * 1000

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

/** 通用来源词表（GET /authors/source-vocabulary）：来源建议=获取渠道平台名
 *  （如「老王论坛」），服务端手动维护的小清单、全员共享（个人片段词已退出
 *  建议场景）；服务端已按固定序返回，前端不再排。设置页词表卡与资产编辑页
 *  来源区快捷选项共用。 */
export function useSourceVocabulary() {
  return useQuery({
    queryKey: SOURCE_VOCABULARY_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1AuthorsSourceVocabulary()),
    staleTime: SOURCE_VOCABULARY_STALE_MS,
  })
}

/** 保存通用来源词表（PUT /authors/source-vocabulary，整体替换；空数组=清空）。
 *  保存成功失效自身键，读侧（设置页/编辑页）即时反映。 */
export function useSaveSourceVocabulary() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: SourceVocabulary) =>
      unwrapSdkResult(putApiV1AuthorsSourceVocabulary({ body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: SOURCE_VOCABULARY_QUERY_KEY }),
  })
}

/** 单作者来源区回显（GET /authors/{authorId}/sources）：该作者 TXT 片段
 *  「来源/出处」区的当前词表；authorId 空=挂起不发请求（编辑页按行调用，
 *  键形 [...AUTHOR_SOURCES_BY_ID_QUERY_KEY, authorId]）。 */
export function useAuthorSourcesById(authorId: string | null | undefined) {
  return useQuery({
    queryKey: [...AUTHOR_SOURCES_BY_ID_QUERY_KEY, authorId ?? ''],
    queryFn: () =>
      unwrapSdkResult(getApiV1AuthorsByAuthorIdSources({ path: { authorId: authorId! } })),
    enabled: !!authorId,
  })
}

/** 保存单作者来源区（PUT /authors/{authorId}/sources，整体替换；空数组=清空）。
 *  保存成功失效该作者的回显键。 */
export function useSaveAuthorSources() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { authorId: string; sources: string[] }) =>
      unwrapSdkResult(
        putApiV1AuthorsByAuthorIdSources({
          path: { authorId: args.authorId },
          body: { sources: args.sources },
        }),
      ),
    onSuccess: (_res, args) =>
      qc.invalidateQueries({ queryKey: [...AUTHOR_SOURCES_BY_ID_QUERY_KEY, args.authorId] }),
  })
}

/** 资产作者关联整体替换（PUT /assets/{assetId}/authors，ADR-0024）：body=
 *  常规作者 ID 全集（空数组=解除全部常规关联；作者须已存在且 type=regular）。
 *  失效资产族（详情 authors/列表作者列/推荐流）与作者族（fileCount 随关联
 *  变化）；200 回 AssetDetail，由调用方按需消费。 */
export function useReplaceAssetAuthors() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (args: { assetId: string; authorIds: string[] }) =>
      unwrapSdkResult(
        putApiV1AssetsByAssetIdAuthors({
          path: { assetId: args.assetId },
          body: { authorIds: args.authorIds },
        }),
      ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ASSETS_QUERY_KEY })
      qc.invalidateQueries({ queryKey: AUTHORS_QUERY_KEY })
    },
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
