/**
 * 搜索页「更多筛选」面板（SearchPage 拆分出的子组件：警戒线 300 行）。
 * 纯渲染 + 状态回调，不调 API（铁律 7：数据一律经 SearchPage 的 hooks 传入）。
 * 类名与原型逐字一致（styles/prototype.css 消费），布局数值不动。
 */

import type { Tag } from '@/api/generated'
import { LOCALE_ZH } from '@/lib/constants'
import {
  ORDER_OPTIONS,
  PLAYS_OPTIONS,
  SEARCH_YEARS,
  SIZE_OPTIONS,
  TAG_MODE_OPTIONS,
  TIME_OPTIONS,
  type SearchFilterState,
} from './search-state'

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

/** 年份区间下拉：切到「按年份区间」档时显示，真实参与 yearFrom/yearTo 查询参数 */
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
  pool: Tag[]
  selected: string[]
  inputOpen: boolean
  onToggle: (tagId: string) => void
  onRemove: (tagId: string) => void
  onOpenInput: () => void
  onCloseInput: () => void
  onAdd: (raw: string) => void
}

/** 标签行：全量标签池 pill（点击切换选中、× 取消选中）+ 「+ 添加」与内联输入互斥切换 */
function TagRow({ pool, selected, inputOpen, onToggle, onRemove, onOpenInput, onCloseInput, onAdd }: TagRowProps) {
  return (
    <FilterRow label="标签">
      {pool.map((t) => (
        <button
          key={t.id ?? t.name}
          type="button"
          className={`pill pill-tag${selected.includes(t.id ?? '') ? ' active' : ''}`}
          onClick={() => onToggle(t.id ?? '')}
        >
          {t.name}
          <span
            className="tag-x"
            title="移除该标签筛选"
            onClick={(e) => {
              e.stopPropagation()
              onRemove(t.id ?? '')
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

export interface SearchFiltersProps {
  /** 面板折叠开关（父组件「更多筛选」按钮驱动，照原型 hidden 语义） */
  hidden: boolean
  state: SearchFilterState
  /** 标签池（服务端全量，按名称升序由父组件排好） */
  tagPool: Tag[]
  tagInputOpen: boolean
  setFilter: <K extends keyof SearchFilterState>(key: K, value: SearchFilterState[K]) => void
  onToggleTag: (tagId: string) => void
  onRemoveTag: (tagId: string) => void
  onOpenTagInput: () => void
  onCloseTagInput: () => void
  onAddTag: (raw: string) => void
}

/** 「更多筛选」面板：顺位/播放/大小/时间/标签模式/标签池（面板折叠由父组件控制 hidden） */
export function SearchFilters({
  hidden,
  state,
  tagPool,
  tagInputOpen,
  setFilter,
  onToggleTag,
  onRemoveTag,
  onOpenTagInput,
  onCloseTagInput,
  onAddTag,
}: SearchFiltersProps) {
  const sortedPool = [...tagPool].sort((a, b) =>
    (a.name ?? '').localeCompare(b.name ?? '', LOCALE_ZH),
  )

  return (
    <div className="search-filters" hidden={hidden}>
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
          onToggle={onToggleTag}
          onRemove={onRemoveTag}
          onOpenInput={onOpenTagInput}
          onCloseInput={onCloseTagInput}
          onAdd={onAddTag}
        />
      </div>
    </div>
  )
}
