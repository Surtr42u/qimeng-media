/**
 * panel-demo 页面间共享的展示小部件（仅原型使用）。
 *
 * V2 动效：指标卡数字滚动（useCountUp）、hover 主色光晕（参照 AssetCard 的
 * ring+shadow 合一改法）、Reveal 入场交错淡入上移（motion 实现，无循环动画）。
 */
import type { ReactNode } from 'react'
import { motion } from 'motion/react'
import { Card } from '@/components/tremor/card'
import { Badge } from '@/components/tremor/badge'
import { cx } from '@/lib/tremor'
import type { MetricCard } from './mock'
import { useCountUp } from './use-count-up'

/** 指标卡：大数字滚动 + 趋势 Badge（up=success / down=error / flat 中性）+ 可选小字补充 */
export function MetricCardView({ label, value, decimals = 0, suffix, delta, deltaType, sub }: MetricCard) {
  const badgeVariant =
    deltaType === 'up' ? 'success' : deltaType === 'down' ? 'error' : 'neutral'
  const animated = useCountUp(value)
  // 千分位 + 指定小数位（value 是数字，格式化只在展示层做）
  const text = animated.toLocaleString('zh-CN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })
  return (
    <Card className="py-5 transition-shadow duration-200 ring-1 ring-transparent hover:shadow-lg hover:shadow-[var(--qm-primary-soft)] hover:ring-[var(--qm-primary-soft)]">
      <div className="flex items-start justify-between gap-2">
        <p className="text-sm text-muted-foreground">{label}</p>
        <Badge variant={badgeVariant}>{delta}</Badge>
      </div>
      <p className="mt-2 text-3xl font-semibold tabular-nums tracking-tight">
        {text}
        {suffix ? (
          <span className="ml-1 text-base font-medium text-muted-foreground">{suffix}</span>
        ) : null}
      </p>
      {sub ? <p className="mt-1.5 text-xs text-muted-foreground">{sub}</p> : null}
    </Card>
  )
}

/** 入场交错延迟：index*60ms，上限 300ms（克制原则：只挂载时播一次） */
const revealDelay = (index: number) => Math.min(index * 60, 300) / 1000

/**
 * 入场动画容器：初始 opacity 0 / y 10，淡入上移。
 * 用于包裹页面各区块（section/Card），index 决定交错次序。
 */
export function Reveal({
  index,
  children,
  className,
}: {
  index: number
  children: ReactNode
  className?: string
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3, ease: 'easeOut', delay: revealDelay(index) }}
      className={className}
    >
      {children}
    </motion.div>
  )
}

/** 排名徽标：前三主色实底，其余中性（数据页小卡与榜单详情页共用同一视觉） */
export function RankBadge({ rank }: { rank: number }) {
  return (
    <span
      className={cx(
        'flex size-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold tabular-nums',
        rank < 3 ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground',
      )}
    >
      {rank + 1}
    </span>
  )
}

/**
 * 分段按钮组（单选高亮）：数据页时间范围 / 榜单详情页排序切换共用。
 * 泛型约束 key，onChange 直接回调选中 key，页面无需自行断言类型。
 */
export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
  ariaLabel,
}: {
  options: Array<{ key: T; label: string }>
  value: T
  onChange: (value: T) => void
  ariaLabel: string
}) {
  return (
    <div
      role="group"
      aria-label={ariaLabel}
      className="inline-flex items-center rounded-md border border-border bg-muted/40 p-0.5"
    >
      {options.map((opt) => (
        <button
          key={opt.key}
          type="button"
          aria-pressed={value === opt.key}
          onClick={() => onChange(opt.key)}
          className={cx(
            'rounded-[5px] px-3 py-1 text-sm font-medium transition-colors',
            value === opt.key
              ? 'bg-primary text-primary-foreground shadow-sm'
              : 'text-muted-foreground hover:bg-accent/50 hover:text-foreground',
          )}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}
