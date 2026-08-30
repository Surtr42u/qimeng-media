/**
 * 浏览筛选共享组件：BrowsePage（榜单条目浏览）与 GalleryPage（相册全量）同源复用。
 *
 * 从 BrowsePage 原型抽出三件套（两段式胶囊筛选卡 / 媒体卡 / 文件预览 Dialog），
 * 筛选状态、级联计数与悬空回落等逻辑留在调用页——本文件只负责渲染，
 * 数据（值计数列表等）全部由 props 传入，保证两个页面行为完全一致。
 */
import { motion } from 'motion/react'
import { RiArrowUpSLine, RiArrowDownSLine } from '@remixicon/react'
import { Card } from '@/components/tremor/card'
import { Badge } from '@/components/tremor/badge'
import { Button } from '@/components/tremor/button'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/tremor/dialog'
import { cx } from '@/lib/tremor'
import {
  BROWSE_SORT_OPTIONS,
  DIMENSION_META,
  type BrowseDimension,
  type BrowseFilters,
  type BrowseSortKey,
  type DetailFile,
  type DimensionValueCount,
} from './mock'

/** 媒体卡 hover 光晕（与 MetricCardView 同一 ring+shadow 合一改法） */
export const CARD_HOVER =
  'transition-shadow duration-200 ring-1 ring-transparent hover:shadow-lg hover:shadow-[var(--qm-primary-soft)] hover:ring-[var(--qm-primary-soft)]'

/** 值行收起态最大高度：约两行胶囊（胶囊行高 ~30px + gap 8px） */
const VALUES_COLLAPSED_MAX_H = 'max-h-[4.75rem]'

/** 胶囊按钮（维度行与值行共用）：单选高亮，紧凑小圆角 */
function Pill({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cx(
        'rounded-full border px-3 py-1 text-xs font-medium transition-colors',
        active
          ? 'border-primary bg-primary text-primary-foreground'
          : 'border-border text-muted-foreground hover:bg-accent/50 hover:text-foreground',
      )}
    >
      {children}
    </button>
  )
}

/** 维度值带计数文案（如「全部 6,309」；计数与名称分色） */
function PillWithCount({
  label,
  count,
  active,
  onClick,
}: {
  label: string
  count: number
  active: boolean
  onClick: () => void
}) {
  return (
    <Pill active={active} onClick={onClick}>
      {label}{' '}
      <span className={cx('ml-0.5 tabular-nums', active ? 'text-primary-foreground/70' : 'opacity-60')}>
        {count.toLocaleString('zh-CN')}
      </span>
    </Pill>
  )
}

/**
 * 两段式胶囊筛选卡（旧版交互桌面化）：
 * - 第一段「维度行」：分区/作品/角色/类型（各带值数量；类型前竖线分隔=文件属性维度）
 * - 第二段「值行」：所选维度的值胶囊（带计数，按计数降序，多行可展开/收起）
 * - 底部排序组（独立于维度筛选）
 */
export function BrowseFilterPanel({
  visibleDims,
  dimensionValueCounts,
  activeDim,
  onActiveDimChange,
  activeCounts,
  filters,
  onFilterChange,
  valuesExpanded,
  onValuesExpandedChange,
  sortKey,
  onSortChange,
}: {
  /** 可见维度（全量池 = 四维；单内容条目 = 仅类型） */
  visibleDims: BrowseDimension[]
  /** 各维度的值种类数（维度行「全部」不计） */
  dimensionValueCounts: Record<BrowseDimension, number>
  activeDim: BrowseDimension
  onActiveDimChange: (dim: BrowseDimension) => void
  /** 当前激活维度的值计数列表（级联口径由 mock 层算好） */
  activeCounts: DimensionValueCount[]
  filters: BrowseFilters
  onFilterChange: (dim: BrowseDimension, value: string) => void
  valuesExpanded: boolean
  onValuesExpandedChange: (expanded: boolean) => void
  sortKey: BrowseSortKey
  onSortChange: (key: BrowseSortKey) => void
}) {
  return (
    <Card className="p-4">
      {visibleDims.length > 1 ? (
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="筛选维度">
          {DIMENSION_META.filter((d) => visibleDims.includes(d.key)).map(({ key, label }) => (
            <span key={key} className="flex items-center gap-1.5">
              {/* 旧版惯例：类型是文件属性维度，与其余聚合维度以竖线分隔 */}
              {key === 'type' ? (
                <span className="mx-1 hidden h-4 w-px bg-border sm:block" aria-hidden={true} />
              ) : null}
              <Pill
                active={activeDim === key}
                onClick={() => onActiveDimChange(key)}
              >
                {label}{' '}
                <span className="ml-0.5 opacity-60 tabular-nums">{dimensionValueCounts[key]}</span>
              </Pill>
            </span>
          ))}
        </div>
      ) : null}

      {/* 值行：所选维度的值胶囊（级联计数，按计数降序）；展开/收起 */}
      <div className="mt-3">
        <div
          className={cx(
            'flex flex-wrap items-center gap-1.5 overflow-hidden transition-[max-height] duration-200',
            !valuesExpanded && activeCounts.length > 8 ? VALUES_COLLAPSED_MAX_H : 'max-h-none',
          )}
        >
          {activeCounts.map(({ value, count }) => (
            <PillWithCount
              key={value}
              label={value}
              count={count}
              active={filters[activeDim] === value}
              onClick={() => onFilterChange(activeDim, value)}
            />
          ))}
        </div>
        {activeCounts.length > 8 ? (
          <button
            type="button"
            onClick={() => onValuesExpandedChange(!valuesExpanded)}
            className="mt-2 inline-flex items-center gap-0.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            {valuesExpanded ? '收起' : '展开'}
            {valuesExpanded ? (
              <RiArrowUpSLine className="size-3.5" aria-hidden={true} />
            ) : (
              <RiArrowDownSLine className="size-3.5" aria-hidden={true} />
            )}
          </button>
        ) : null}
      </div>

      {/* 排序组（独立于维度筛选，单选） */}
      <div
        className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-border pt-3"
        role="group"
        aria-label="按维度排序"
      >
        <span className="mr-0.5 text-xs text-muted-foreground">排序</span>
        {BROWSE_SORT_OPTIONS.map((opt) => (
          <Pill key={opt} active={sortKey === opt} onClick={() => onSortChange(opt)}>
            {opt}
          </Pill>
        ))}
      </div>
    </Card>
  )
}

/** 简化媒体卡：渐变占位缩略 + 类型 Badge + 大小/浏览量小字，整卡点击开预览 */
export function BrowseMediaCard({ file, onOpen }: { file: DetailFile; onOpen: () => void }) {
  return (
    <Card className={cx('overflow-hidden p-0', CARD_HOVER)}>
      <button
        type="button"
        onClick={onOpen}
        className="block w-full text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        {/* 渐变占位缩略：正式版接入签名直链缩略图 */}
        <div className="flex aspect-video w-full items-center justify-center bg-gradient-to-br from-blue-500/20 via-violet-500/20 to-amber-500/15">
          <span className="text-xs text-muted-foreground">缩略图占位</span>
        </div>
        <div className="p-3">
          <div className="flex items-center gap-1.5">
            <Badge variant="neutral">{file.mediaType}</Badge>
            <span className="ml-auto text-[11px] tabular-nums text-muted-foreground">
              {file.views.toLocaleString('zh-CN')} 浏览
            </span>
          </div>
          <p className="mt-1.5 truncate text-sm font-medium">{file.name}</p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {file.size}
            {file.durationMin > 0 ? ` · ${file.durationMin} 分钟` : ''}
          </p>
        </div>
      </button>
    </Card>
  )
}

/** 文件预览 Dialog：占位缩略 + 元数据 + 关闭（V2 预览视图模式搬移） */
export function BrowsePreviewDialog({ file, onClose }: { file: DetailFile; onClose: () => void }) {
  return (
    <Dialog open={true} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        {/* 视图挂载动效：fade + 轻缩放，只播一次（与 V2 预览一致） */}
        <motion.div
          initial={{ opacity: 0, scale: 0.97 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.18, ease: 'easeOut' }}
        >
          <DialogHeader>
            <DialogTitle className="truncate text-base">{file.name}</DialogTitle>
          </DialogHeader>
          <div className="mt-4 flex aspect-video w-full items-center justify-center rounded-md bg-gradient-to-br from-blue-500/20 via-violet-500/20 to-amber-500/15">
            <span className="text-sm text-muted-foreground">缩略图占位</span>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Badge variant="default">{file.mediaType}</Badge>
            <p className="text-xs text-muted-foreground">
              {file.size}
              {file.durationMin > 0 ? ` · ${file.durationMin} 分钟` : ''} · {file.time} ·{' '}
              {file.views.toLocaleString('zh-CN')} 次浏览
            </p>
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            正式版将接入签名直链预览，当前为原型占位。
          </p>
          <DialogFooter className="mt-4">
            <Button variant="secondary" onClick={onClose}>
              关闭
            </Button>
          </DialogFooter>
        </motion.div>
      </DialogContent>
    </Dialog>
  )
}
