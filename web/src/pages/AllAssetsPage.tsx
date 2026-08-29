/**
 * 全部页（媒体全量列表）。
 *
 * 页面状态全部存 URL searchParams（交互规格约定：刷新不丢、Tab 切回自动恢复）：
 * - 搜索 q / 排序 sort+order / 全部筛选条件（mediaType/source/tagIds/tagMode/viewRange/
 *   playRange/sizeRange/dateFrom/dateTo/yearFrom/yearTo/favorite）都映射到 URL；
 * - 列数走 localStorage（全局列数，多页共用，见 use-grid-columns.ts）。
 *
 * 网格按 modifiedAt 日期分组（协议 groupByDate 服务端忽略→客户端分组，见 GroupedAssetGrid）；
 * 无限滚动（useAssets 封装，sentinel 在底部 600px 预取）。
 * 空态区分"库无文件"（引导去管理页扫描）与"筛选无结果"。
 */

import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { ArrowDownUp, LayoutGrid, Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupedAssetGrid } from '@/components/asset/GroupedAssetGrid'
import { FilterSheet } from '@/components/asset/FilterSheet'
import { useGridColumns } from '@/components/asset/use-grid-columns'
import { InfiniteScrollSentinel } from '@/components/misc/InfiniteScrollSentinel'
import { useAssets, type AssetFilters } from '@/hooks/use-assets'
import type { DetailBatchState } from '@/pages/RecommendPage'

/* ------------------------------ 文案常量（交互规格来源） ------------------------------ */

const TEXT_SEARCH_PLACEHOLDER = '搜索文件名 / 标签 / 作者…'
const TEXT_FILTER = '筛选'
const TEXT_SORT_DEFAULT = '默认排序'
const TEXT_SORT_FILE_DATE = '文件时间'
const TEXT_SORT_ADDED_DATE = '入库时间'
const TEXT_SORT_VIEW_COUNT = '观看次数'
const TEXT_SORT_PLAY_COUNT = '播放次数'
const TEXT_SORT_SIZE = '大小'
const TEXT_SORT_NAME = '文件名'
const TEXT_EMPTY_LIBRARY = '库中还没有媒体文件'
const TEXT_EMPTY_LIBRARY_HINT = '请到管理页添加媒体库并扫描'
const TEXT_EMPTY_FILTERED = '没有符合筛选条件的媒体'
const TEXT_EMPTY_FILTERED_HINT = '试试调整筛选条件或清除搜索'
const TEXT_GO_ADMIN = '去管理页'
const TEXT_COLUMNS_TEMPLATE = '{n} 列'
const TEXT_SORT_ASC = '升序'
const TEXT_SORT_DESC = '降序'

/* ------------------------------ 行为阈值（交互规格来源） ------------------------------ */

/** 搜索防抖（ms）：输入停顿 400ms 后写 URL（交互规格定值） */
const SEARCH_DEBOUNCE_MS = 400

/** 排序键（协议 GET /assets sort 七键全量；DOMAIN_RULES §3 排序口径） */
const SORT_OPTIONS = [
  { value: 'default', label: TEXT_SORT_DEFAULT },
  { value: 'fileDate', label: TEXT_SORT_FILE_DATE },
  { value: 'addedDate', label: TEXT_SORT_ADDED_DATE },
  { value: 'viewCount', label: TEXT_SORT_VIEW_COUNT },
  { value: 'playCount', label: TEXT_SORT_PLAY_COUNT },
  { value: 'sizeBytes', label: TEXT_SORT_SIZE },
  { value: 'name', label: TEXT_SORT_NAME },
] as const

/* ------------------------------ URL searchParams 映射 ------------------------------ */

/** URL 键名（值域与协议 query 参数一一对应；tagIds 逗号分隔） */
const PARAM_KEY = {
  q: 'q',
  sort: 'sort',
  order: 'order',
} as const
const FILTER_KEYS = [
  'mediaType',
  'source',
  'tagIds',
  'tagMode',
  'viewRange',
  'playRange',
  'sizeRange',
  'dateFrom',
  'dateTo',
  'yearFrom',
  'yearTo',
  'favorite',
] as const

/** URL → AssetFilters（favorite '1'/'0'、tagIds 逗号串、year* 数字） */
function parseFilters(params: URLSearchParams): AssetFilters {
  const filters: AssetFilters = {}
  for (const key of FILTER_KEYS) {
    const raw = params.get(key)
    if (raw == null || raw === '') continue
    switch (key) {
      case 'tagIds':
        filters.tagIds = raw.split(',')
        break
      case 'favorite':
        filters.favorite = raw === '1'
        break
      case 'yearFrom':
      case 'yearTo': {
        const n = Number(raw)
        if (Number.isFinite(n)) filters[key] = n
        break
      }
      default:
        ;(filters as Record<string, unknown>)[key] = raw
    }
  }
  return filters
}

/** AssetFilters → URL 写入（undefined/空值删除键；非筛选键透传保留） */
function patchSearchParams(params: URLSearchParams, filters: AssetFilters): URLSearchParams {
  const next = new URLSearchParams(params)
  for (const key of FILTER_KEYS) {
    const value = (filters as Record<string, unknown>)[key]
    if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) {
      next.delete(key)
    } else if (Array.isArray(value)) {
      next.set(key, value.join(','))
    } else if (key === 'favorite') {
      next.set(key, value ? '1' : '0')
    } else {
      next.set(key, String(value))
    }
  }
  return next
}

/** 是否存在任一筛选条件（空态文案分支依据） */
function hasActiveFilters(filters: AssetFilters, q: string): boolean {
  return (
    q.trim() !== '' ||
    filters.mediaType != null ||
    filters.source != null ||
    (filters.tagIds?.length ?? 0) > 0 ||
    filters.viewRange != null ||
    filters.playRange != null ||
    filters.sizeRange != null ||
    filters.dateFrom != null ||
    filters.dateTo != null ||
    filters.yearFrom != null ||
    filters.yearTo != null ||
    filters.favorite != null
  )
}

export default function AllAssetsPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const { columns, cycle } = useGridColumns()
  const [filterOpen, setFilterOpen] = useState(false)

  const filters = useMemo(() => parseFilters(searchParams), [searchParams])
  const q = searchParams.get(PARAM_KEY.q) ?? ''
  const sort = searchParams.get(PARAM_KEY.sort) ?? 'default'
  const order = searchParams.get(PARAM_KEY.order) ?? 'desc'

  // 搜索输入框的本地草稿：防抖 400ms 后写 URL（避免每次击键重建 queryKey）
  const [qDraft, setQDraft] = useState(q)
  useEffect(() => setQDraft(q), [q])
  useEffect(() => {
    const timer = setTimeout(() => {
      if (qDraft === (searchParams.get(PARAM_KEY.q) ?? '')) return
      const next = new URLSearchParams(searchParams)
      if (qDraft === '') next.delete(PARAM_KEY.q)
      else next.set(PARAM_KEY.q, qDraft)
      setSearchParams(next, { replace: true })
    }, SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [qDraft, searchParams, setSearchParams])

  const assetsQuery = useAssets({ ...filters, sort: sort as AssetFilters['sort'], order: order as AssetFilters['order'] })

  /** 筛选面板 onChange → 写 URL（即改即刷新；Sheet 不关闭，交互规格） */
  const handleFiltersChange = (next: AssetFilters) => {
    setSearchParams(patchSearchParams(searchParams, next), { replace: true })
  }

  /** 重置筛选（清空筛选区 + 搜索；排序/列数保留） */
  const handleReset = () => {
    const next = new URLSearchParams(searchParams)
    for (const key of [...FILTER_KEYS, PARAM_KEY.q]) next.delete(key)
    setSearchParams(next, { replace: true })
    setQDraft('')
  }

  const setSort = (value: string) => {
    const next = new URLSearchParams(searchParams)
    if (value === 'default') next.delete(PARAM_KEY.sort)
    else next.set(PARAM_KEY.sort, value)
    setSearchParams(next, { replace: true })
  }

  const toggleOrder = () => {
    const next = new URLSearchParams(searchParams)
    if (order === 'desc') next.set(PARAM_KEY.order, 'asc')
    else next.delete(PARAM_KEY.order) // 'desc' 是服务端默认值，省略键即可
    setSearchParams(next, { replace: true })
  }

  const items = assetsQuery.data?.items ?? []
  const filteredOut = hasActiveFilters(filters, q)

  const openDetail = (item: (typeof items)[number], index: number) => {
    if (!item.id) return
    navigate(`/detail/${item.id}`, {
      state: { items, index, from: '/all' } satisfies DetailBatchState,
    })
  }

  return (
    <div className="flex flex-col">
      {/* 工具区：吸顶（分组头 top-11 避让，见 GroupedAssetGrid GROUP_HEADER_STICKY_TOP_CLASS。
          z-30 高于组头 z-10：滚动时工具区常驻，筛选入口始终可达） */}
      <div className="sticky top-0 z-30 flex h-11 items-center gap-2 border-b border-[var(--qm-divider)] bg-[var(--qm-surface)]/90 px-[var(--qm-space-3)] backdrop-blur">
        <div className="relative flex-1 min-w-0">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-[var(--qm-text-muted)]" aria-hidden />
          <Input
            value={qDraft}
            onChange={(e) => setQDraft(e.target.value)}
            placeholder={TEXT_SEARCH_PLACEHOLDER}
            className="pl-8"
          />
        </div>
        <Select value={sort} onValueChange={setSort}>
          <SelectTrigger size="sm" className="shrink-0">
            <SelectValue />
          </SelectTrigger>
          <SelectContent align="end">
            {SORT_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button
          size="icon-sm"
          variant="secondary"
          onClick={toggleOrder}
          aria-label={order === 'desc' ? TEXT_SORT_DESC : TEXT_SORT_ASC}
          title={order === 'desc' ? TEXT_SORT_DESC : TEXT_SORT_ASC}
        >
          <ArrowDownUp className="size-3.5" aria-hidden />
        </Button>
        <Button
          size="sm"
          variant="secondary"
          onClick={cycle}
          aria-label={TEXT_COLUMNS_TEMPLATE.replace('{n}', String(columns))}
          title={TEXT_COLUMNS_TEMPLATE.replace('{n}', String(columns))}
        >
          <LayoutGrid className="size-3.5" aria-hidden />
          {TEXT_COLUMNS_TEMPLATE.replace('{n}', String(columns))}
        </Button>
        <Button size="sm" onClick={() => setFilterOpen(true)}>
          {TEXT_FILTER}
        </Button>
      </div>

      {/* 内容区 */}
      <div className="p-[var(--qm-space-3)]">
        {assetsQuery.isLoading ? (
          <div className="grid grid-cols-2 gap-[var(--qm-space-1)] sm:grid-cols-3 lg:grid-cols-4">
            {Array.from({ length: 12 }).map((_, i) => (
              <Skeleton key={i} className="aspect-square w-full" />
            ))}
          </div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center gap-[var(--qm-space-2)] py-20 text-center">
            <p className="text-sm font-semibold">{filteredOut ? TEXT_EMPTY_FILTERED : TEXT_EMPTY_LIBRARY}</p>
            <p className="text-xs text-[var(--qm-text-muted)]">
              {filteredOut ? TEXT_EMPTY_FILTERED_HINT : TEXT_EMPTY_LIBRARY_HINT}
            </p>
            {!filteredOut ? (
              <Button variant="outline" size="sm" asChild>
                <Link to="/admin">{TEXT_GO_ADMIN}</Link>
              </Button>
            ) : null}
          </div>
        ) : (
          <>
            <GroupedAssetGrid items={items} columns={columns} onOpen={openDetail} />
            <InfiniteScrollSentinel
              hasNextPage={assetsQuery.hasNextPage}
              isLoading={assetsQuery.isFetchingNextPage}
              onLoadMore={() => void assetsQuery.fetchNextPage()}
            />
          </>
        )}
      </div>

      <FilterSheet
        open={filterOpen}
        onOpenChange={setFilterOpen}
        value={filters}
        onChange={handleFiltersChange}
        onReset={handleReset}
      />
    </div>
  )
}
