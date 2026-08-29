/**
 * CPU 卡：整体百分比 + 逐核横向条组（每核一根柱；核数多时滚动区不撑爆卡片）。
 */

import { MetricBar } from '@/components/admin/MetricBar'

/** 逐核条组最大可视高度（每核一行 1.25rem，24 核时约 30rem——超出滚动） */
const PER_CORE_MAX_HEIGHT = '20rem'

export interface CpuCardProps {
  cpuPercent?: number
  perCore?: number[]
}

export function CpuCard({ cpuPercent, perCore }: CpuCardProps) {
  const cores = perCore ?? []
  return (
    <div className="flex flex-col gap-3">
      <MetricBar label="CPU 总使用率" valueText={`${Math.round(cpuPercent ?? 0)}%`} ratio={(cpuPercent ?? 0) / 100} />
      {cores.length > 0 && (
        <div className="flex flex-col gap-1.5 overflow-y-auto pr-1" style={{ maxHeight: PER_CORE_MAX_HEIGHT }}>
          {cores.map((value, index) => (
            <div key={index} className="flex items-center gap-2">
              <span className="w-6 shrink-0 text-right font-mono text-[10px] text-[var(--qm-text-muted)]">
                #{index + 1}
              </span>
              <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-[var(--qm-chip-bg)]">
                <div
                  className="h-full rounded-full bg-[var(--qm-primary)]"
                  style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
                />
              </div>
              <span className="w-9 shrink-0 font-mono text-[10px] text-[var(--qm-text-muted)]">
                {Math.round(value)}%
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
