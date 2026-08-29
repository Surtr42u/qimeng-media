/**
 * 通用监控指标条（CPU/内存/磁盘卡共用）：标签 + 数值 + 比例条。
 * 第 2 次使用（三类卡都要"数值+条"样式）即提取本组件（AI_README 复用优先级）。
 */

import type { ReactNode } from 'react'

export interface MetricBarProps {
  label: ReactNode
  /** 右侧主数值文案（如 "64%"、"18.2 GB / 512 GB"） */
  valueText: string
  /** 填充比例 0~1（负值/超界 clamp，避免异常数据撑破条） */
  ratio: number
  /** 可选子文案（条下方灰字） */
  subText?: string
}

export function MetricBar({ label, valueText, ratio, subText }: MetricBarProps) {
  const percent = Math.min(1, Math.max(0, ratio)) * 100
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-2">
        <span className="min-w-0 truncate text-xs text-[var(--qm-text-muted)]">{label}</span>
        <span className="font-mono text-sm font-semibold">{valueText}</span>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-[var(--qm-chip-bg)]">
        <div className="h-full rounded-full bg-[var(--qm-primary)]" style={{ width: `${percent}%` }} />
      </div>
      {subText && <p className="text-xs text-[var(--qm-text-muted)]">{subText}</p>}
    </div>
  )
}
