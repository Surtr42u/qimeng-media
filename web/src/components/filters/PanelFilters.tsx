/**
 * 「更多筛选」面板（搜索页/相册页共用组件；2026-09-17 用户拍板两页面板内容
 * 对齐同构，自 pages/SearchFilters.tsx 迁入 components/filters）。首行=排序
 * 三档，其后 顺位/播放次数/文件大小/时间范围/标签模式/标签；纯渲染 + 状态回调，
 * 不调 API（铁律 7：数据一律经 usePanelFilters hook 传入）。
 * 类名与原型逐字一致（styles/prototype.css 消费），布局数值不动。
 */

import type { Tag } from '@/api/generated'
import { LOCALE_ZH } from '@/lib/constants'
import {
  ORDER_OPTIONS,
  PLAYS_OPTIONS,
  SEARCH_YEARS,
  SIZE_OPTIONS,
  SORT_OPTIONS,
  TAG_MODE_OPTIONS,
  TIME_OPTIONS,
  type PanelFilterState,
} from '@/lib/panel-filters'
import { Pill } from '@/components/ui/pill'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'

/** 新建标签输入的字数上限（客户端自定值，原型沿袭——协议无标签名长度上限，
 *  服务端只拦空名；截断只发生在输入框层，超长粘贴不炸布局即可，2026-09-20
 *  全库审查自裸魔法值提常量）。 */
const TAG_NAME_MAX_LENGTH = 12

function FilterRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="f-row">
      <span className="f-label">{label}</span>
      <div className="f-opts">{children}</div>
    </div>
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
      <Select value={from} onValueChange={(v) => onChange('yearFrom', v)}>
        <SelectTrigger aria-label="起始年份" />
        <SelectContent>
          {SEARCH_YEARS.map((y) => (
            <SelectItem key={y} value={y}>
              {y} 年
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <span className="f-years-sep">至</span>
      <Select value={to} onValueChange={(v) => onChange('yearTo', v)}>
        <SelectTrigger aria-label="结束年份" />
        <SelectContent>
          {SEARCH_YEARS.map((y) => (
            <SelectItem key={y} value={y}>
              {y} 年
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
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
        <Pill
          key={t.id ?? t.name}
          className="pill-tag"
          active={selected.includes(t.id ?? '')}
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
        </Pill>
      ))}
      {inputOpen ? (
        <input
          className="tag-input"
          type="text"
          placeholder="新标签，回车添加"
          maxLength={TAG_NAME_MAX_LENGTH}
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
        <Pill className="pill-add" onClick={onOpenInput}>
          + 添加
        </Pill>
      )}
    </FilterRow>
  )
}

export interface PanelFiltersProps {
  /** 面板折叠开关（父组件「更多筛选」按钮驱动，照原型 hidden 语义） */
  hidden: boolean
  state: PanelFilterState
  /** 标签池（服务端全量，按名称升序由 hook 排好序前原样传入，组件内排序） */
  tagPool: Tag[]
  tagInputOpen: boolean
  setFilter: <K extends keyof PanelFilterState>(key: K, value: PanelFilterState[K]) => void
  onToggleTag: (tagId: string) => void
  onRemoveTag: (tagId: string) => void
  onOpenTagInput: () => void
  onCloseTagInput: () => void
  onAddTag: (raw: string) => void
}

/** 「更多筛选」面板：排序/顺位/播放次数/大小/时间/标签模式/标签池（面板折叠由父组件控制 hidden） */
export function PanelFilters({
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
}: PanelFiltersProps) {
  const sortedPool = [...tagPool].sort((a, b) =>
    (a.name ?? '').localeCompare(b.name ?? '', LOCALE_ZH),
  )

  return (
    <div className="search-filters" hidden={hidden}>
      <div>
        <FilterRow label="排序">
          {SORT_OPTIONS.map((v) => (
            <Pill key={v} active={state.sort === v} onClick={() => setFilter('sort', v)}>
              {v}
            </Pill>
          ))}
        </FilterRow>
        <FilterRow label="顺位">
          {ORDER_OPTIONS.map((v) => (
            <Pill key={v} active={state.order === v} onClick={() => setFilter('order', v)}>
              {v}
            </Pill>
          ))}
        </FilterRow>
        <FilterRow label="播放次数">
          {PLAYS_OPTIONS.map((v) => (
            <Pill key={v} active={state.plays === v} onClick={() => setFilter('plays', v)}>
              {v}
            </Pill>
          ))}
        </FilterRow>
        <FilterRow label="文件大小">
          {SIZE_OPTIONS.map((v) => (
            <Pill key={v} active={state.size === v} onClick={() => setFilter('size', v)}>
              {v}
            </Pill>
          ))}
        </FilterRow>
        <FilterRow label="时间范围">
          {TIME_OPTIONS.map((v) => (
            <Pill key={v} active={state.time === v} onClick={() => setFilter('time', v)}>
              {v}
            </Pill>
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
            <Pill key={v} active={state.tagMode === v} onClick={() => setFilter('tagMode', v)}>
              {v}
            </Pill>
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
