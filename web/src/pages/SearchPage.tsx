import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { ChevronDownIcon } from '@/components/shell/icons'
import { LOCALE_ZH } from '@/lib/constants'
import { MOCK_ALBUM_FILES, MOCK_SEARCH_TAGS, type MockAlbumFile } from '@/pages/mock'

/** 搜索筛选状态机（照 app.js SEARCH_STATE） */
interface SearchFilterState {
  type: string
  sort: string
  order: string
  plays: string
  size: string
  time: string
  tagMode: string
  tags: string[]
  yearFrom: string
  yearTo: string
}

const STYPE_TABS = ['综合', '视频', '图片', '音频'] as const
const SORT_TABS = ['综合排序', '最多点击'] as const
const ORDER_OPTIONS = ['降序', '升序'] as const
const PLAYS_OPTIONS = ['全部', '未播放', '1-5', '5-20', '>20'] as const
const SIZE_OPTIONS = ['全部', '<1MB', '1-10MB', '10-50MB', '>50MB'] as const
const TIME_OPTIONS = ['全部', '今天', '本周', '本月', '近三月', '本年', '按年份区间'] as const
const TAG_MODE_OPTIONS = ['模糊', '精确'] as const

// 2016~2026 倒序 11 个年份（照 app.js SEARCH_YEARS）
const SEARCH_YEARS = Array.from({ length: 11 }, (_, i) => String(2026 - i))

// 初值与「新搜索重置」共用同一份（照原型 resetSearchState：新查询不继承旧筛选）
function newSearchState(): SearchFilterState {
  return {
    type: '综合',
    sort: '综合排序',
    order: '降序',
    plays: '全部',
    size: '全部',
    time: '全部',
    tagMode: '模糊',
    tags: [],
    yearFrom: '2016',
    yearTo: '2026',
  }
}

// 照 app.js hitTag：标签命中四维任一值或名称子串
function hitTag(file: MockAlbumFile, tag: string): boolean {
  return Object.values(file.tags).includes(tag) || file.name.includes(tag)
}

function FilterRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="f-row">
      <span className="f-label">{label}</span>
      <div className="f-opts">{children}</div>
    </div>
  )
}

function FilterPill({ value, active, onClick }: { value: string; active: boolean; onClick: () => void }) {
  return (
    <button type="button" className={`pill${active ? ' active' : ''}`} onClick={onClick}>
      {value}
    </button>
  )
}

/** 年份区间下拉：仅记录不过滤（原型语义，区间类筛选无对应 mock 数据） */
function YearRange({
  from,
  to,
  onChange,
}: {
  from: string
  to: string
  onChange: (key: 'yearFrom' | 'yearTo', value: string) => void
}) {
  return (
    <span className="f-years">
      <select aria-label="起始年份" value={from} onChange={(e) => onChange('yearFrom', e.target.value)}>
        {SEARCH_YEARS.map((y) => (
          <option key={y} value={y}>
            {y} 年
          </option>
        ))}
      </select>
      <span className="f-years-sep">至</span>
      <select aria-label="结束年份" value={to} onChange={(e) => onChange('yearTo', e.target.value)}>
        {SEARCH_YEARS.map((y) => (
          <option key={y} value={y}>
            {y} 年
          </option>
        ))}
      </select>
    </span>
  )
}

interface TagRowProps {
  pool: string[]
  selected: string[]
  inputOpen: boolean
  onToggle: (tag: string) => void
  onRemove: (tag: string) => void
  onOpenInput: () => void
  onCloseInput: () => void
  onAdd: (raw: string) => void
}

/** 标签行：池 pill（× 删除级联）+ 「+ 添加」与内联输入互斥切换 */
function TagRow({ pool, selected, inputOpen, onToggle, onRemove, onOpenInput, onCloseInput, onAdd }: TagRowProps) {
  return (
    <FilterRow label="标签">
      {pool.map((t) => (
        <button
          key={t}
          type="button"
          className={`pill pill-tag${selected.includes(t) ? ' active' : ''}`}
          onClick={() => onToggle(t)}
        >
          {t}
          <span
            className="tag-x"
            title="删除标签"
            onClick={(e) => {
              e.stopPropagation()
              onRemove(t)
            }}
          >
            ×
          </span>
        </button>
      ))}
      {inputOpen ? (
        <input
          className="tag-input"
          type="text"
          placeholder="新标签，回车添加"
          maxLength={12}
          autoFocus
          onKeyDown={(e) => {
            // 回车入池并收起（原型 innerHTML 重渲染后输入框复位）；Esc 直接还原
            if (e.key === 'Enter') onAdd(e.currentTarget.value)
            if (e.key === 'Enter' || e.key === 'Escape') onCloseInput()
          }}
          onBlur={(e) => {
            if (e.currentTarget.value.trim()) onAdd(e.currentTarget.value)
            onCloseInput()
          }}
        />
      ) : (
        <button type="button" className="pill pill-add" onClick={onOpenInput}>
          + 添加
        </button>
      )}
    </FilterRow>
  )
}

/**
 * 搜索结果页（原型 #page-search 移植）：顶栏搜索框回车进入，q 变化整体重置筛选。
 * 类型与标签筛选真实生效；区间类（播放/大小/时间/年份）仅切换样式不参与过滤
 * （原型明确语义：无对应 mock 数据）。阶段 A 纯内存态，不调 API。
 */
export default function SearchPage() {
  const [searchParams] = useSearchParams()
  const q = (searchParams.get('q') ?? '').trim()
  const [state, setState] = useState<SearchFilterState>(newSearchState)
  const [panelOpen, setPanelOpen] = useState(false)
  const [tagPool, setTagPool] = useState<string[]>(MOCK_SEARCH_TAGS)
  const [tagInputOpen, setTagInputOpen] = useState(false)

  // 新搜索整体重置（数据态重置，非 DOM 操作，属 useEffect 合理场景）
  useEffect(() => {
    setState(newSearchState())
    setPanelOpen(false)
    setTagInputOpen(false)
  }, [q])

  const setFilter = <K extends keyof SearchFilterState>(key: K, value: SearchFilterState[K]) =>
    setState((s) => ({ ...s, [key]: value }))

  const toggleTag = (tag: string) =>
    setState((s) => ({
      ...s,
      tags: s.tags.includes(tag) ? s.tags.filter((t) => t !== tag) : [...s.tags, tag],
    }))

  // 照 app.js addTag：trim + 剔除危险字符 + 去重（浏览中新标签自动入池）
  const addTag = (raw: string) => {
    const v = raw.trim().replace(/[<>&"']/g, '')
    if (!v) return
    setTagPool((pool) => (pool.includes(v) ? pool : [...pool, v]))
  }

  // 照 app.js removeTag：从池删除并级联清理筛选选中态
  const removeTag = (tag: string) => {
    setTagPool((pool) => pool.filter((t) => t !== tag))
    setState((s) => ({ ...s, tags: s.tags.filter((t) => t !== tag) }))
  }

  // 池展示按名称升序（照 app.js sortedTags，DOMAIN_RULES 标签排序口径）
  const sortedPool = useMemo(
    () => [...tagPool].sort((a, b) => a.localeCompare(b, LOCALE_ZH)),
    [tagPool],
  )

  // 基础池 = 标签叠加后的集合（照 app.js searchPool），类型计数与结果网格都基于它
  const pool = useMemo(() => {
    if (state.tags.length === 0) return MOCK_ALBUM_FILES
    return MOCK_ALBUM_FILES.filter((f) =>
      state.tagMode === '精确'
        ? state.tags.every((t) => hitTag(f, t))
        : state.tags.some((t) => hitTag(f, t)),
    )
  }, [state.tags, state.tagMode])

  const files = useMemo(() => {
    let list = pool
    if (state.type !== '综合') list = list.filter((f) => f.tags.type === state.type)
    if (state.sort === '最多点击') {
      list = [...list].sort(
        (a, b) => parseInt(b.views.replace(/,/g, ''), 10) - parseInt(a.views.replace(/,/g, ''), 10),
      )
    }
    if (state.order === '升序') list = [...list].reverse()
    return list
  }, [pool, state.type, state.sort, state.order])

  // 类型徽标计数：综合显示池总数，其余按类型统计（照 app.js renderSearchTypes）
  const countFor = (t: string) =>
    t === '综合' ? pool.length : pool.filter((f) => f.tags.type === t).length

  return (
    <div className="page" id="page-search">
      <div className="stype-row">
        {STYPE_TABS.map((t) => (
          <button
            key={t}
            type="button"
            className={`stype${state.type === t ? ' active' : ''}`}
            data-stype={t}
            onClick={() => setFilter('type', t)}
          >
            {t}
            {/* 「综合」无计数徽标（照原型） */}
            {t !== '综合' ? <span className="stype-count">{countFor(t)}</span> : null}
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
      <div className="search-filters" hidden={!panelOpen}>
        <div>
          <FilterRow label="顺位">
            {ORDER_OPTIONS.map((v) => (
              <FilterPill key={v} value={v} active={state.order === v} onClick={() => setFilter('order', v)} />
            ))}
          </FilterRow>
          <FilterRow label="播放次数">
            {PLAYS_OPTIONS.map((v) => (
              <FilterPill key={v} value={v} active={state.plays === v} onClick={() => setFilter('plays', v)} />
            ))}
          </FilterRow>
          <FilterRow label="文件大小">
            {SIZE_OPTIONS.map((v) => (
              <FilterPill key={v} value={v} active={state.size === v} onClick={() => setFilter('size', v)} />
            ))}
          </FilterRow>
          <FilterRow label="时间范围">
            {TIME_OPTIONS.map((v) => (
              <FilterPill key={v} value={v} active={state.time === v} onClick={() => setFilter('time', v)} />
            ))}
            {state.time === '按年份区间' ? (
              <YearRange
                from={state.yearFrom}
                to={state.yearTo}
                onChange={(key, value) => setFilter(key, value)}
              />
            ) : null}
          </FilterRow>
          <FilterRow label="标签模式">
            {TAG_MODE_OPTIONS.map((v) => (
              <FilterPill key={v} value={v} active={state.tagMode === v} onClick={() => setFilter('tagMode', v)} />
            ))}
          </FilterRow>
          <TagRow
            pool={sortedPool}
            selected={state.tags}
            inputOpen={tagInputOpen}
            onToggle={toggleTag}
            onRemove={removeTag}
            onOpenInput={() => setTagInputOpen(true)}
            onCloseInput={() => setTagInputOpen(false)}
            onAdd={addTag}
          />
        </div>
      </div>
      <div className="grid">
        {q === '' ? (
          <p className="grid-empty">在顶部搜索框输入关键词开始搜索</p>
        ) : files.length ? (
          files.map((f) => (
            <MediaCard key={f.name} cover={f.cover} title={f.name} duration={f.duration} up={f.up} date={f.date} />
          ))
        ) : (
          <p className="grid-empty">没有匹配的内容，放宽一点筛选条件试试。</p>
        )}
      </div>
    </div>
  )
}
