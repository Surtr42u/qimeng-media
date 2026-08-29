/**
 * 内存卡：used/total → 百分比 + 条。
 */

import { formatBytes } from '@/lib/format'
import { MetricBar } from '@/components/admin/MetricBar'

export interface MemCardProps {
  memUsedBytes?: number
  memTotalBytes?: number
}

export function MemCard({ memUsedBytes, memTotalBytes }: MemCardProps) {
  const ratio = memTotalBytes && memTotalBytes > 0 ? (memUsedBytes ?? 0) / memTotalBytes : 0
  return (
    <MetricBar
      label="内存"
      valueText={`${formatBytes(memUsedBytes)} / ${formatBytes(memTotalBytes)}`}
      ratio={ratio}
      subText={`已用 ${Math.round(ratio * 100)}%`}
    />
  )
}
