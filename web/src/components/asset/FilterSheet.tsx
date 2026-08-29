/**
 * 筛选面板（全部页 FilterSheet）。
 *
 * 数据流约定：筛选值全部由页面读写 URL searchParams（交互规格：页面状态存 URL，Sheet 内
 * 即改即刷新且不关闭）；本组件只收发 AssetFilters 形状的值，不碰 router（保持纯净可复用）。
 *
 * 分组（交互规格顺序）：
 * 1. 媒体类型（单选）   2. 出处分区（单选，协议不支持多值）  3. 标签（多选 + 模糊/精确切换）
 * 4. 大小档（单选）     5. 观看/播放档（单选）               6. 时间范围（起点预设 + 年份区间）
 * 7. 收藏开关
 */

import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Switch } from '@/components/ui/switch'
import type { AssetFilters } from '@/hooks/use-assets'
import { useSources } from '@/hooks/use-sources'
import { useTags } from '@/hooks/use-tags'

/* ------------------------------ 文案常量（交互规格来源） ------------------------------ */

const TEXT_MEDIA_TYPE_ALL = '全部'
const TEXT_MEDIA_TYPE_IMAGE = '图片'
const TEXT_MEDIA_TYPE_VIDEO = '视频'
const TEXT_MEDIA_TYPE_ANIMATED = '动图'
const TEXT_SOURCE_ALL = '全部'
const TEXT_SOURCE_UNKNOWN = '其他'
const TEXT_SIZE_ALL = '全部'
const TEXT_PRESET_ALL = '全部'
const TEXT_TAG_MODE_FUZZY = '模糊'
const TEXT_TAG_MODE_EXACT = '精确'
const TEXT_BTN_RESET = '重置筛选'
const TEXT_FAVORITE_ONLY = '只看收藏'
const TEXT_SECTION_SOURCE = '出处'
const TEXT_SECTION_TAGS = '标签'
const TEXT_SECTION_SIZE = '大小'
const TEXT_SECTION_VIEW = '观看次数'
const TEXT_SECTION_PLAY = '播放次数'
const TEXT_SECTION_TIME = '时间范围'
const TEXT_YEAR_FROM = '起始年份'
const TEXT_YEAR_TO = '结束年份'
const TEXT_TAG_MODE_PREFIX = '匹配：'
const TEXT_SEARCH_TAG_PLACEHOLDER = '搜索标签…'

/* ------------------------------ 档位常量（协议枚举值） ------------------------------ */

/** 媒体类型单选档：undefined=全部（协议 GET /assets mediaType 省略=不限） */
const MEDIA_TYPE_OPTIONS = [
  { value: undefined, label: TEXT_MEDIA_TYPE_ALL },
  { value: 'image' as const, label: TEXT_MEDIA_TYPE_IMAGE },
  { value: 'video' as const, label: TEXT_MEDIA_TYPE_VIDEO },
  { value: 'animated_image' as const, label: TEXT_MEDIA_TYPE_ANIMATED },
]

/** 大小档（协议 SizeRange 枚举全量） */
const SIZE_OPTIONS = [
  { value: undefined, label: TEXT_SIZE_ALL },
  { value: 'lt1m' as const, label: '< 1 MB' },
  { value: 'm1to10' as const, label: '1–10 MB' },
  { value: 'm10to50' as const, label: '10–50 MB' },
  { value: 'gt50m' as const, label: '> 50 MB' },
]

/** 观看/播放档（协议 CountRange：none=0/low=1-5/mid=5-20/high=>20） */
const COUNT_OPTIONS = [
  { value: undefined, label: TEXT_PRESET_ALL },
  { value: 'none' as const, label: '0 次' },
  { value: 'low' as const, label: '1–5 次' },
  { value: 'mid' as const, label: '5–20 次' },
  { value: 'high' as const, label: '> 20 次' },
]

/**
 * 时间预设档。dateFrom 起点计算（DOMAIN_RULES 口径；zh-CN 周一对齐）：
 * 今天=当日 00:00 本地；本周=周一 00:00；本月=1 日 00:00；近三月=今天-90 天；本年=1 月 1 日。
 */
const TIME_PRESET_OPTIONS = [
  { value: '', label: TEXT_PRESET_ALL, dateFrom: null },
  { value: 'today', label: '今天', dateFrom: (d: Date) => startOfDay(d) },
  { value: 'week', label: '本周', dateFrom: (d: Date) => startOfWeekMonday(d) },
  { value: 'month', label: '本月', dateFrom: (d: Date) => new Date(d.getFullYear(), d.getMonth(), 1) },
  { value: 'quarter', label: '近三月', dateFrom: (d: Date) => addDays(startOfDay(d), -90) },
  { value: 'year', label: '本年', dateFrom: (d: Date) => new Date(d.getFullYear(), 0, 1) },
] as const

/** 时间预设 → 当前选中值（匹配 filter.dateFrom） */
function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

/** 周一 00:00（zh-CN 周一对齐：getDay() 0=周日 → 周一偏移 (day+6)%7） */
function startOfWeekMonday(date: Date): Date {
  const today = startOfDay(date)
  today.setDate(today.getDate() - ((today.getDay() + 6) % 7))
  return today
}

/** 日期加减（近三月 = 今天 - 90 天） */
function addDays(date: Date, days: number): Date {
  const result = new Date(date)
  result.setDate(result.getDate() + days)
  return result
}

/** 当前激活的时间预设（倒推：dateFrom 与哪个预设的起点一致），无匹配返回 '' */
function activeTimePreset(dateFrom: string | undefined): string {
  if (!dateFrom) return ''
  const now = new Date()
  for (const option of TIME_PRESET_OPTIONS) {
    if (option.dateFrom) {
      const from = option.dateFrom(now)
      if (Math.abs(from.getTime() - new Date(dateFrom).getTime()) < 1000) return option.value
    }
  }
  return ''
}

/* ------------------------------ 本地小控件 ------------------------------ */

/** 单选 chip：选中=主色实底，未选=软底（筛选面板统一样式） */
function FilterChip({
  label,
  selected,
  onClick,
}: {
  label: string
  selected: boolean
  onClick: () => void
}) {
  return (
    <Button size="sm" variant={selected ? 'default' : 'secondary'} onClick={onClick}>
      {label}
    </Button>
  )
}

/** 小节标题 */
function SectionLabel({ children }: { children: string }) {
  return (
    <h3 className="text-xs font-semibold text-[var(--qm-text-muted)]">{children}</h3>
  )
}

/* ------------------------------ 主组件 ------------------------------ */

interface FilterSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 当前筛选值（= URL searchParams 解析结果） */
  value: AssetFilters
  /** 任一条件变化即回调（页面写 URL；已约定不关闭 Sheet） */
  onChange: (next: AssetFilters) => void
  /** 重置全部筛选（含搜索/排序；页面负责清 URL） */
  onReset: () => void
}

export function FilterSheet({ open, onOpenChange, value, onChange, onReset }: FilterSheetProps) {
  const tagsQuery = useTags()
  const sourcesQuery = useSources({ enabled: open })
  // 本地标签搜索词（打开面板时的过滤输入，不写 URL——面板关闭即弃）
  const [tagKeyword, setTagKeyword] = useState('')

  // useSources 当前是"后端完成前直连实现"，返回 unknown[]；按协议 SourceCount 形状
  // 断言使用（TODO 联调：协议 SDK getApiV1Sources 已生成，联调后 hooks 应替换实现）。
  const sources = (sourcesQuery.data ?? []) as Array<{ name?: string | null; fileCount?: number }>

  const tags = tagsQuery.data ?? []
  const currentTagIds = value.tagIds ?? []
  const currentTagIdSet = new Set(currentTagIds)

  /** 合并更新：undefined = 清除该键 */
  const patch = (partial: Partial<AssetFilters>) => onChange({ ...value, ...partial })

  /** 标签多选切换（整体替换 tagIds） */
  const toggleTag = (tagId: string) => {
    const next = new Set(currentTagIds)
    if (next.has(tagId)) next.delete(tagId)
    else next.add(tagId)
    patch({ tagIds: next.size > 0 ? [...next] : undefined })
  }

  /** 时间预设选中（计算起点 → dateFrom；'全部'= 清除） */
  const selectTimePreset = (optionValue: string) => {
    const option = TIME_PRESET_OPTIONS.find((o) => o.value === optionValue)
    if (!option?.dateFrom) {
      onChange({ ...value, dateFrom: undefined, dateTo: undefined })
      return
    }
    onChange({ ...value, dateFrom: option.dateFrom(new Date()).toISOString(), dateTo: undefined })
  }

  const activePreset = activeTimePreset(value.dateFrom)
  const filteredTags = tagKeyword
    ? tags.filter((tag) => (tag.name ?? '').includes(tagKeyword))
    : tags

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="bottom" className="max-h-[85dvh] overflow-y-auto gap-5">
        <SheetHeader>
          <SheetTitle>筛选</SheetTitle>
        </SheetHeader>

        {/* 1. 媒体类型（单选） */}
        <div className="flex flex-col gap-2">
          <SectionLabel>媒体类型</SectionLabel>
          <div className="flex flex-wrap gap-2">
            {MEDIA_TYPE_OPTIONS.map((option) => (
              <FilterChip
                key={option.label}
                label={option.label}
                selected={(value.mediaType ?? undefined) === option.value}
                onClick={() => patch({ mediaType: option.value })}
              />
            ))}
          </div>
        </div>

        {/* 2. 出处（单选；协议 source 参数是单值，不支持多选） */}
        <div className="flex flex-col gap-2">
          <SectionLabel>{TEXT_SECTION_SOURCE}</SectionLabel>
          <div className="flex flex-wrap gap-2">
            <FilterChip
              label={TEXT_SOURCE_ALL}
              selected={!value.source}
              onClick={() => patch({ source: undefined })}
            />
            {sources.map((source) => {
              // source=null（无出处桶，显示"其他"）→ 查询值传协议字面量 "其他"：
              // 服务端 assets.go newAssetFilters 把 source=="其他" 翻译成 source_is_other 标志
              const filterValue = source.name ?? TEXT_SOURCE_UNKNOWN
              return (
                <FilterChip
                  key={filterValue}
                  label={filterValue}
                  selected={value.source === filterValue}
                  onClick={() => patch({ source: filterValue })}
                />
              )
            })}
            {sourcesQuery.isError ? (
              <span className="self-center text-xs text-[var(--qm-text-muted)]">
                出处列表暂时不可用（useSources 联调中）
              </span>
            ) : null}
          </div>
        </div>

        {/* 3. 标签（多选 + 模糊/精确） */}
        <div className="flex flex-col gap-2">
          <SectionLabel>{TEXT_SECTION_TAGS}</SectionLabel>
          <Input
            value={tagKeyword}
            onChange={(e) => setTagKeyword(e.target.value)}
            placeholder={TEXT_SEARCH_TAG_PLACEHOLDER}
          />
          <div className="flex items-center gap-2">
            <span className="text-xs text-[var(--qm-text-muted)]">{TEXT_TAG_MODE_PREFIX}</span>
            <FilterChip
              label={TEXT_TAG_MODE_FUZZY}
              selected={(value.tagMode ?? 'fuzzy') === 'fuzzy'}
              onClick={() => patch({ tagMode: 'fuzzy' })}
            />
            <FilterChip
              label={TEXT_TAG_MODE_EXACT}
              selected={value.tagMode === 'exact'}
              onClick={() => patch({ tagMode: 'exact' })}
            />
          </div>
          <div className="flex flex-wrap gap-2">
            {filteredTags.map((tag) => {
              const tagId = tag.id ?? ''
              const selected = currentTagIdSet.has(tagId)
              return (
                <Badge
                  key={tagId}
                  variant={selected ? 'default' : 'secondary'}
                  role="button"
                  tabIndex={0}
                  aria-pressed={selected}
                  className="h-[var(--qm-chip-height)] cursor-pointer px-2.5 text-sm"
                  onClick={() => toggleTag(tagId)}
                  onKeyDown={(e) => {
                    // 键盘可达性：Enter/空格 等效点击（chip 是 button 语义）
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      toggleTag(tagId)
                    }
                  }}
                >
                  {tag.name}
                  {tag.fileCount != null ? ` (${tag.fileCount})` : ''}
                </Badge>
              )
            })}
          </div>
        </div>

        {/* 4. 大小档（单选） */}
        <div className="flex flex-col gap-2">
          <SectionLabel>{TEXT_SECTION_SIZE}</SectionLabel>
          <div className="flex flex-wrap gap-2">
            {SIZE_OPTIONS.map((option) => (
              <FilterChip
                key={option.label}
                label={option.label}
                selected={(value.sizeRange ?? undefined) === option.value}
                onClick={() => patch({ sizeRange: option.value })}
              />
            ))}
          </div>
        </div>

        {/* 5. 观看/播放档（单选） */}
        <div className="flex flex-col gap-2">
          <SectionLabel>{TEXT_SECTION_VIEW}</SectionLabel>
          <div className="flex flex-wrap gap-2">
            {COUNT_OPTIONS.map((option) => (
              <FilterChip
                key={option.label}
                label={option.label}
                selected={(value.viewRange ?? undefined) === option.value}
                onClick={() => patch({ viewRange: option.value })}
              />
            ))}
          </div>
          <SectionLabel>{TEXT_SECTION_PLAY}</SectionLabel>
          <div className="flex flex-wrap gap-2">
            {COUNT_OPTIONS.map((option) => (
              <FilterChip
                key={option.label}
                label={option.label}
                selected={(value.playRange ?? undefined) === option.value}
                onClick={() => patch({ playRange: option.value })}
              />
            ))}
          </div>
        </div>

        {/* 6. 时间范围：起点预设 + 年份区间 */}
        <div className="flex flex-col gap-2">
          <SectionLabel>{TEXT_SECTION_TIME}</SectionLabel>
          <div className="flex flex-wrap gap-2">
            {TIME_PRESET_OPTIONS.map((option) => (
              <FilterChip
                key={option.label}
                label={option.label}
                selected={activePreset === option.value}
                onClick={() => selectTimePreset(option.value)}
              />
            ))}
          </div>
          <div className="flex items-center gap-2">
            <Input
              type="number"
              placeholder={TEXT_YEAR_FROM}
              value={value.yearFrom ?? ''}
              onChange={(e) => {
                const n = e.target.value === '' ? undefined : Number(e.target.value)
                onChange({ ...value, yearFrom: n })
              }}
              className="w-28"
            />
            <Input
              type="number"
              placeholder={TEXT_YEAR_TO}
              value={value.yearTo ?? ''}
              onChange={(e) => {
                const n = e.target.value === '' ? undefined : Number(e.target.value)
                onChange({ ...value, yearTo: n })
              }}
              className="w-28"
            />
          </div>
        </div>

        {/* 7. 收藏开关 */}
        <div className="flex items-center justify-between">
          <span className="text-sm">{TEXT_FAVORITE_ONLY}</span>
          <Switch
            checked={value.favorite ?? false}
            onCheckedChange={(checked) => patch({ favorite: checked || undefined })}
          />
        </div>

        <Button variant="outline" className="w-full" onClick={onReset}>
          {TEXT_BTN_RESET}
        </Button>
      </SheetContent>
    </Sheet>
  )
}
