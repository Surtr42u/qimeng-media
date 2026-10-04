/**
 * 检索词表 hooks（词表维护页消费，ADR-0033 端点 / App ADR-0035 web 移植；
 * 铁律 7：组件不直接调 API）。与 use-authors 的「通用来源词表」
 * （/authors/source-vocabulary，获取渠道建议词）是两张不同的表——本族是
 * 匹配引擎的自定义出处组检索词层（出处组/变体/角色/别名 + 停用词追加层）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1SourcesCustomGroups,
  putApiV1SourcesCustomGroups,
  type CustomSourceGroups,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { SOURCE_GROUPS_QUERY_KEY } from '@/lib/query-keys'

/** 词表缓存时效：手动维护的低频配置（同 SOURCE_VOCABULARY_STALE_MS 口径），
 *  5 分钟内重复进页不重发请求；保存后由 mutation 同步写缓存 + 失效重拉 */
const SOURCE_GROUPS_STALE_MS = 5 * 60 * 1000

/** 检索词表全量（GET /sources/custom-groups；词表维护页进页回显） */
export function useSourceGroups() {
  return useQuery({
    queryKey: SOURCE_GROUPS_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1SourcesCustomGroups()),
    staleTime: SOURCE_GROUPS_STALE_MS,
  })
}

/**
 * 整体保存（PUT /sources/custom-groups，整体替换语义——提交的 groups+stopWords
 * 就是生效词层；空数组=清空回内置基线。服务端规范化后会自动后台重算）。
 * 成功即把提交体同步写进缓存（页面立即回落到「所见即所存」形态，不闪旧值），
 * 再失效重拉带回服务端规范化形态（trim/去重/canonical 自并入）——即 App 端
 * 「保存后静默回读」的 web 等价实现。
 */
export function useSaveSourceGroups() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CustomSourceGroups) =>
      unwrapSdkResult(putApiV1SourcesCustomGroups({ body })),
    onSuccess: (_res, body) => {
      qc.setQueryData(SOURCE_GROUPS_QUERY_KEY, body)
      void qc.invalidateQueries({ queryKey: SOURCE_GROUPS_QUERY_KEY })
    },
  })
}
