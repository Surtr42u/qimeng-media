import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import type { CountRange, SizeRange } from '@/api/generated'
import { MediaCard } from '@/components/media/MediaCard'
import { ChevronDownIcon } from '@/components/shell/icons'
import {
  assetToCard,
  useAssetsInfinite,
  useAssetsTotal,
  type AssetListParams,
  type MediaType,
} from '@/hooks/use-assets'
import { useCreateTag, useTags } from '@/hooks/use-tags'
import { DEFAULT_PAGE_SIZE } from '@/lib/constants'
import { SearchFilters } from './SearchFilters'
import { SORT_TABS, newSearchState, type SearchFilterState } from './search-state'

/**
 * 搜索结果页（原型 #page-search 移植）：顶栏搜索框回车进入，q 变化整体重置筛选。
 * 数据源 = GET /assets 全参数（q/类型/排序/顺位/区间/年份/标签全部真实传参，阶段 B 接真）；
 * 类型 tab 四档：综合（不传 mediaType）/视频/动图/图片——协议无 audio 类型且数据库无音频
 * 记录（旧 App 的音频档在数据模型里本就无实体，原型残留，见 DOMAIN_RULES 类型口径）。
 * 「更多筛选」拆分在 SearchFilters（本文件警戒线内）。
 */

/** 类型 tab 文案 → 协议 MediaType（「综合」不传；MediaType 枚举 image/animated_image/video，无 audio） */
const TYPE_TO_MEDIA: Record<string, MediaType | undefined> = {
  综合: undefined,
  视频: 'video',
  动图: 'animated_image',
  图片: 'image',
}

/** 播放/浏览次数档（文案=原型）→ 协议 CountRange；边界以 browse.sql 为准：none=0/low=1-5/mid=5-20/high=>20 */
const PLAYS_TO_RANGE: Record<string, CountRange | undefined> = {
  全部: undefined,
  未播放: 'none',
  '1-5': 'low',
  '5-20': 'mid',
  '>20': 'high',
}

/** 文件大小档（文案=原型）→ 协议 SizeRange；边界以 browse.sql 为准：lt1m<1MB/m1to10<10MB/m10to50<50MB/gt50m */
const SIZE_TO_RANGE: Record<string, SizeRange | undefined> = {
  全部: undefined,
  '<1MB': 'lt1m',
  '1-10MB': 'm1to10',
  '10-50MB': 'm10to50',
  '>50MB': 'gt50m',
}

/** 本地日历日 → yyyy-MM-dd（服务端语义：本地日历日，dateTo 含当日全天——assets.go newAssetFilters） */
function localDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 时间范围档 → dateFrom/dateTo（「全部」/「按年份区间」不产日期，年份走 yearFrom/yearTo） */
function dateRangeFor(time: string): { dateFrom?: string; dateTo?: string } | undefined {
  const today = new Date()
  const to = localDate(today)
  if (time === '今天') return { dateFrom: to, dateTo: to }
  if (time === '本周') {
    const monday = new Date(today)
    monday.setDate(today.getDate() - ((today.getDay() + 6) % 7)) // 周一为一周之始（周日 getDay()=0 → 回 6 天）
    return { dateFrom: localDate(monday), dateTo: to }
  }
  if (time === '本月') {
    const first = new Date(today.getFullYear(), today.getMonth(), 1)
    return { dateFrom: localDate(first), dateTo: to }
  }
  if (time === '近三月') {
    const from = new Date(today)
    from.setMonth(today.getMonth() - 3)
    return { dateFrom: localDate(from), dateTo: to }
  }
  if (time === '本年') return { dateFrom: `${today.getFullYear()}-01-01`, dateTo: to }
  return undefined
}

/** 新建标签前的名称清洗（照 app.js addTag：trim + 剔除危险字符；空名丢弃） */
function cleanTagName(raw: string): string {
  return raw.trim().replace(/[<>&"']/g, '')
}

export default function SearchPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const q = (searchParams.get('q') ?? '').trim()
  const [state, setState] = useState<SearchFilterState>(newSearchState)
  const [panelOpen, setPanelOpen] = useState(false)
  const [tagInputOpen, setTagInputOpen] = useState(false)

  // 数据源：标签池（全量）/类型徽标计数（limit=1 取 totalMatched）
  const { data: tagPool = [] } = useTags()
  const { data: totalVideo = 0 } = useAssetsTotal('video')
  const { data: totalAnimated = 0 } = useAssetsTotal('animated_image')
  const { data: totalImage = 0 } = useAssetsTotal('image')
  const createTag = useCreateTag()

  // 新搜索整体重置（数据态重置，非 DOM 操作，属 useEffect 合理场景）
  useEffect(() => {
    setState(newSearchState())
    setPanelOpen(false)
    setTagInputOpen(false)
  }, [q])

  const setFilter = <K extends keyof SearchFilterState>(key: K, value: SearchFilterState[K]) =>
    setState((s) => ({ ...s, [key]: value }))

  const toggleTag = (tagId: string) =>
    setState((s) => ({
      ...s,
      tags: s.tags.includes(tagId) ? s.tags.filter((t) => t !== tagId) : [...s.tags, tagId],
    }))

  // 池来自服务端全量（useTags），× 语义调整为「取消选中」——删除全局标签是管理操作，不在筛选面板
  const removeTag = (tagId: string) => setState((s) => ({ ...s, tags: s.tags.filter((t) => t !== tagId) }))

  // 新标签：清洗 → 同名已存在则直接选中（服务端按 name 去重，避免重复创建）；否则 POST 后按返回 id 选中
  const addTag = (raw: string) => {
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
        // 创建失败（如服务端拒绝）静默忽略：不打断搜索流程，标签输入框已收起
      })
  }

  /** 筛选状态 → GET /assets 参数（唯一组装点；q 为空时页码区不发起查询） */
  const listParams = useMemo<AssetListParams>(() => {
    const p: AssetListParams = { q, limit: DEFAULT_PAGE_SIZE }
    const mt = TYPE_TO_MEDIA[state.type]
    if (mt) p.mediaType = mt
    // 排序：综合=default、最多点击=viewCount（顺位 order 真实传参）
    p.sort = state.sort === '最多点击' ? 'viewCount' : 'default'
    p.order = state.order === '升序' ? 'asc' : 'desc'
    const vr = PLAYS_TO_RANGE[state.plays]
    // 该行选项为「未播放/1-5/5-20/>20」——播放次数语义（play 事件），
    // 映射 playRange 而非 viewRange（DOMAIN_RULES §3 观看/播放两行口径分开）
    if (vr) p.playRange = vr
    const sr = SIZE_TO_RANGE[state.size]
    if (sr) p.sizeRange = sr
    const dr = dateRangeFor(state.time)
    if (dr) {
      if (dr.dateFrom) p.dateFrom = dr.dateFrom
      if (dr.dateTo) p.dateTo = dr.dateTo
    }
    if (state.time === '按年份区间') {
      p.yearFrom = Number(state.yearFrom)
      p.yearTo = Number(state.yearTo)
    }
    if (state.tags.length > 0) {
      p.tagIds = state.tags
      p.tagMode = state.tagMode === '精确' ? 'exact' : 'fuzzy'
    }
    return p
  }, [q, state])

  const { data: pages, isLoading, isFetching, fetchNextPage, hasNextPage } = useAssetsInfinite(listParams, q !== '')
  const items = useMemo(() => pages?.pages.flatMap((pg) => pg.items ?? []) ?? [], [pages])

  // 类型 tab 结构（综合无徽标照原型；徽标 = 该类型资产总数，与当前筛选无关）
  const typeTabs = useMemo(
    () => [
      { label: '综合', total: undefined },
      { label: '视频', total: totalVideo },
      { label: '动图', total: totalAnimated },
      { label: '图片', total: totalImage },
    ],
    [totalVideo, totalAnimated, totalImage],
  )

  return (
    <div className="page" id="page-search">
      <div className="stype-row">
        {typeTabs.map((t) => (
          <button
            key={t.label}
            type="button"
            className={`stype${state.type === t.label ? ' active' : ''}`}
            data-stype={t.label}
            onClick={() => setFilter('type', t.label)}
          >
            {t.label}
            {/* 「综合」无计数徽标（照原型） */}
            {t.total !== undefined ? <span className="stype-count">{t.total}</span> : null}
          </button>
        ))}
      </div>
      <div className="s-toolbar">
        <div className="s-sort">
          {SORT_TABS.map((s) => (
            <button
              key={s}
              type="button"
              className={`sort-pill${state.sort === s ? ' active' : ''}`}
              data-sort={s}
              onClick={() => setFilter('sort', s)}
            >
              {s}
            </button>
          ))}
        </div>
        <button
          type="button"
          className={`more-filter${panelOpen ? ' open' : ''}`}
          onClick={() => setPanelOpen((v) => !v)}
        >
          更多筛选
          <ChevronDownIcon />
        </button>
      </div>
      <SearchFilters
        hidden={!panelOpen}
        state={state}
        tagPool={tagPool}
        tagInputOpen={tagInputOpen}
        setFilter={setFilter}
        onToggleTag={toggleTag}
        onRemoveTag={removeTag}
        onOpenTagInput={() => setTagInputOpen(true)}
        onCloseTagInput={() => setTagInputOpen(false)}
        onAddTag={addTag}
      />
      <div className="grid">
        {q === '' ? (
          <p className="grid-empty">在顶部搜索框输入关键词开始搜索</p>
        ) : items.length ? (
          items.map((a) => (
            <MediaCard
              key={a.id}
              {...assetToCard(a)}
              onClick={() => a.id && navigate(`/app/asset/${a.id}`)}
            />
          ))
        ) : isLoading ? (
          <p className="grid-empty">加载中…</p>
        ) : (
          <p className="grid-empty">没有匹配的内容，放宽一点筛选条件试试。</p>
        )}
      </div>
      {q !== '' && hasNextPage ? (
        <p className="grid-empty">
          <button
            className="pill"
            type="button"
            disabled={isFetching}
            onClick={() => fetchNextPage()}
          >
            {isFetching ? '加载中…' : '加载更多'}
          </button>
          <span className="pill-count">共 {items.length} 项</span>
        </p>
      ) : null}
    </div>
  )
}
