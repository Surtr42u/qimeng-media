/**
 * 推荐偏好 hooks（设置页「推荐偏好」卡消费；铁律 7：组件不直接调 API）。
 * 数据源 GET/PUT /recommendations/prefs：9 维权重 + maxRandom，PUT 为全量整体替换语义。
 * 预设/默认值逐字抄自 DOMAIN_RULES §1.3 表（「逐字遵守」——改动须同步领域规则与测试）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1RecommendationsPrefs,
  putApiV1RecommendationsPrefs,
  type RecommendPrefs,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export const PREFS_QUERY_KEY = ['api/v1/recommendations/prefs'] as const

/** 9 维键序（E4 后无滑杆、只留预设：现用于预设激活判定的字段遍历顺序
 *  （SettingsPage isActivePreset）；与 DOMAIN_RULES §1.3 表格列序一致，PUT 全量提交） */
export const PREFS_KEYS = [
  'tagRelevance',
  'tagCollection',
  'engagement',
  'recency',
  'likeScore',
  'discovery',
  'freshness',
  'browseDepth',
  'maxRandom',
] as const satisfies readonly (keyof RecommendPrefs)[]

/** 预设·均衡推荐（系统默认）：tagRel/tagColl/engage/recency/like/discov/fresh/depth/random */
export const PRESET_BALANCED: RecommendPrefs = {
  tagRelevance: 0.22, tagCollection: 0.15, engagement: 0.10, recency: 0.15,
  likeScore: 0.05, discovery: 0.20, freshness: 0.05, browseDepth: 0.03, maxRandom: 0.30,
}

/** 预设·高记忆流行 */
export const PRESET_MEMORY: RecommendPrefs = {
  tagRelevance: 0.15, tagCollection: 0.10, engagement: 0.20, recency: 0.25,
  likeScore: 0.10, discovery: 0.05, freshness: 0.05, browseDepth: 0.05, maxRandom: 0.10,
}

/** 预设·深度探索 */
export const PRESET_EXPLORE: RecommendPrefs = {
  tagRelevance: 0.30, tagCollection: 0.20, engagement: 0.05, recency: 0.05,
  likeScore: 0.02, discovery: 0.30, freshness: 0.02, browseDepth: 0.05, maxRandom: 0.40,
}

/** 预设·新鲜优先 */
export const PRESET_FRESH: RecommendPrefs = {
  tagRelevance: 0.10, tagCollection: 0.05, engagement: 0.05, recency: 0.10,
  likeScore: 0.02, discovery: 0.25, freshness: 0.20, browseDepth: 0.03, maxRandom: 0.35,
}

/** 预设列表（UI 展示顺序；「均衡推荐」为默认项） */
export const RECOMMEND_PRESETS: { label: string; prefs: RecommendPrefs }[] = [
  { label: '均衡推荐', prefs: PRESET_BALANCED },
  { label: '高记忆流行', prefs: PRESET_MEMORY },
  { label: '深度探索', prefs: PRESET_EXPLORE },
  { label: '新鲜优先', prefs: PRESET_FRESH },
]

/** GET 失败/未返回时的兜底默认（= 均衡推荐） */
export const DEFAULT_PREFS: RecommendPrefs = PRESET_BALANCED

/** 读取当前 9 维偏好 */
export function usePrefs() {
  return useQuery({
    queryKey: PREFS_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1RecommendationsPrefs()),
  })
}

/** 保存（全量 9 字段整体替换，协议语义）；成功后使缓存失效驱动下轮刷新 */
export function useSavePrefs() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (prefs: RecommendPrefs) =>
      unwrapSdkResult(putApiV1RecommendationsPrefs({ body: prefs })),
    onSuccess: () => qc.invalidateQueries({ queryKey: PREFS_QUERY_KEY }),
  })
}
