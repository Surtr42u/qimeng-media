/**
 * 「更多筛选」面板共用状态 hook（搜索页/相册页同构消费；铁律 7：数据获取走
 * hooks，组件只渲染）。2026-09-17 面板内容两页对齐后自 SearchPage 抽离——
 * 排序/顺位/播放次数/文件大小/时间范围/标签模式/标签 七行状态 + 标签池增选。
 * 各页特有维（搜索：分区/类型 tab；相册：分区/作者/角色/类型 facets）留在页面。
 */

import { useCallback, useMemo, useState } from 'react'
import { toast } from 'sonner'
import type { AssetListParams, AssetSort } from '@/hooks/use-assets'
import { useCreateTag, useTags } from '@/hooks/use-tags'
import { newPanelState, type PanelFilterState, type SortOption } from '@/lib/panel-filters'
import { dateRangeFor, PLAYS_TO_RANGE, SIZE_TO_RANGE } from '@/lib/search-mapping'

/** 排序三档文案 → 协议 AssetSort。「默认」=fileDate（媒体文件时间）——拍板原话
 * 「默认=default 即时间排序那个」的时间语义指媒体时间；协议 'default' 键实为入库
 * 时间 created_at，真库实测入库序把新导入视频批次顶置、日期分组碎裂（2026-09-17
 * 第三百零七笔根因）。协议八排序键全保留，DOMAIN_RULES §3。 */
const SORT_TO_ASSET: Record<SortOption, AssetSort> = {
  默认: 'fileDate',
  观看次数: 'viewCount',
  文件大小: 'sizeBytes',
}

/** 新建标签前的名称清洗（照 app.js addTag：trim + 剔除危险字符；空名丢弃） */
function cleanTagName(raw: string): string {
  return raw.trim().replace(/[<>&"']/g, '')
}

/** 面板状态机 + 标签池增选（回调全部 useCallback 稳定引用，供调用方安全列入依赖） */
export function usePanelFilters() {
  const [state, setState] = useState<PanelFilterState>(newPanelState)
  const [tagInputOpen, setTagInputOpen] = useState(false)
  const { data: tagPool = [] } = useTags()
  const createTag = useCreateTag()

  const setFilter = useCallback(
    <K extends keyof PanelFilterState>(key: K, value: PanelFilterState[K]) =>
      setState((s) => ({ ...s, [key]: value })),
    [],
  )

  const toggleTag = useCallback(
    (tagId: string) =>
      setState((s) => ({
        ...s,
        tags: s.tags.includes(tagId) ? s.tags.filter((t) => t !== tagId) : [...s.tags, tagId],
      })),
    [],
  )

  // 池来自服务端全量（useTags），× 语义=「取消选中」——删除全局标签是管理操作，不在筛选面板
  const removeTag = useCallback(
    (tagId: string) => setState((s) => ({ ...s, tags: s.tags.filter((t) => t !== tagId) })),
    [],
  )

  // 新标签：清洗 → 同名已存在直接选中（服务端按 name 去重，避免重复创建）；
  // 否则 POST 后按返回 id 选中；创建失败静默忽略（不打断筛选流程，输入框已收起）
  const addTag = useCallback(
    (raw: string) => {
      const v = cleanTagName(raw)
      if (!v) return
      const existing = tagPool.find((t) => t.name === v)
      if (existing?.id) {
        setState((s) => (s.tags.includes(existing.id!) ? s : { ...s, tags: [...s.tags, existing.id!] }))
        return
      }
      void createTag
        .mutateAsync(v)
        .then((tag) => {
          if (tag.id) setState((s) => (s.tags.includes(tag.id!) ? s : { ...s, tags: [...s.tags, tag.id!] }))
        })
        .catch(() => {
          // 创建失败（网络/409/500）不再静默：用户视角是「标签神秘没选上」，
          // toast 报错但不打断筛选流程（输入框照常收起，可重试）
          toast.error(`标签「${v}」创建失败，请重试`)
        })
    },
    [tagPool, createTag],
  )

  const openTagInput = useCallback(() => setTagInputOpen(true), [])
  const closeTagInput = useCallback(() => setTagInputOpen(false), [])

  /** 重置回初值（搜索页新查询整体重置用；标签输入框一并收起） */
  const reset = useCallback(() => {
    setState(newPanelState())
    setTagInputOpen(false)
  }, [])

  return useMemo(
    () => ({
      state,
      tagPool,
      tagInputOpen,
      setFilter,
      toggleTag,
      removeTag,
      addTag,
      openTagInput,
      closeTagInput,
      reset,
    }),
    [state, tagPool, tagInputOpen, setFilter, toggleTag, removeTag, addTag, openTagInput, closeTagInput, reset],
  )
}

/** 面板状态 → GET /assets 参数（口径单源，搜索页/相册页共用；分区/类型等页面维由调用方自拼）。
 *  播放次数档映射 playRange 而非 viewRange——该行是播放次数语义（play 事件，
 *  DOMAIN_RULES §3 观看/播放两行口径分开）；年份区间仅在「按年份区间」档传参
 *  （初值年份段非筛选语义，恒传会错误收窄结果）；标签仅选中时随 tagIds/tagMode 生效。 */
export function panelAssetParams(s: PanelFilterState): AssetListParams {
  const p: AssetListParams = {
    sort: SORT_TO_ASSET[s.sort],
    order: s.order === '升序' ? 'asc' : 'desc',
  }
  const vr = PLAYS_TO_RANGE[s.plays]
  if (vr) p.playRange = vr
  const sr = SIZE_TO_RANGE[s.size]
  if (sr) p.sizeRange = sr
  const dr = dateRangeFor(s.time)
  if (dr) {
    if (dr.dateFrom) p.dateFrom = dr.dateFrom
    if (dr.dateTo) p.dateTo = dr.dateTo
  }
  if (s.time === '按年份区间') {
    p.yearFrom = Number(s.yearFrom)
    p.yearTo = Number(s.yearTo)
  }
  if (s.tags.length > 0) {
    p.tagIds = s.tags
    p.tagMode = s.tagMode === '精确' ? 'exact' : 'fuzzy'
  }
  return p
}
