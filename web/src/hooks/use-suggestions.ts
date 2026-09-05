/**
 * 搜索补全 hooks（GET /api/v1/search/suggestions；铁律 7：组件不直接调 API）。
 * 两种形态，语义权威 = api/openapi.yaml 该端点 description：
 * - useSearchSuggestions：输入态补全，q 子串命中五维候选（防抖由调用方做，
 *   本 hook 只负责按 trim 后的词取数与缓存隔离）；
 * - useRecommendSearchWords：空态「推荐搜索」，recommend=true 由服务端随机
 *   抽取五维候选（LEGACY 随机语义，每次重新取数换一批）。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1SearchSuggestions } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { SEARCH_SUGGESTIONS_QUERY_KEY } from '@/lib/query-keys'

/** 输入态补全条数（对齐协议默认 10；上限 50 为协议侧语义，此处不触达） */
const SUGGEST_INPUT_LIMIT = 10

/** 空态推荐词条数（旧版语义 = 随机 10 条五维混合） */
const RECOMMEND_WORDS_LIMIT = 10

/** 输入态补全缓存时效：回到最近输过的词直接命中缓存秒出，不重发请求 */
const SUGGEST_INPUT_STALE_MS = 60 * 1000

/** 输入态补全（q 非空才发请求；recommend 参数不传 = 服务端按普通补全处理） */
export function useSearchSuggestions(q: string, enabled: boolean) {
  const trimmed = q.trim()
  return useQuery({
    // 键用 trim 后的词："ab" 与 "ab " 命中同一缓存条目
    queryKey: [...SEARCH_SUGGESTIONS_QUERY_KEY, 'input', trimmed],
    queryFn: () =>
      unwrapSdkResult(
        getApiV1SearchSuggestions({ query: { q: trimmed, limit: SUGGEST_INPUT_LIMIT } }),
      ),
    enabled: enabled && trimmed !== '',
    staleTime: SUGGEST_INPUT_STALE_MS,
  })
}

/**
 * 空态「推荐搜索」词（recommend=true + 空 q，服务端随机五维候选）。
 * staleTime 默认 0：面板每次重新打开（enabled false→true）都重取，
 * 随机词换一批——这是「随机推荐」语义的一部分，不是缺省偷懒。
 */
export function useRecommendSearchWords(enabled: boolean) {
  return useQuery({
    queryKey: [...SEARCH_SUGGESTIONS_QUERY_KEY, 'recommend'],
    queryFn: () =>
      unwrapSdkResult(
        getApiV1SearchSuggestions({ query: { q: '', recommend: true, limit: RECOMMEND_WORDS_LIMIT } }),
      ),
    enabled,
  })
}
