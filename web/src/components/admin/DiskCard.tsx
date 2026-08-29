/**
 * 磁盘卡：每挂载点一行（短名 + used/total + 条 + 百分比）。
 */

import type { SystemStatus } from '@/api/generated'
import { formatBytes } from '@/lib/format'
import { MetricBar } from '@/components/admin/MetricBar'

/** 挂载点路径 → 短名（Windows "C:\" → "C:"，Unix "/mnt/data" → "data"；异常回退原串） */
export function mountShortName(mount: string | undefined): string {
  if (!mount) return '--'
  const segments = mount.split(/[\\/]/).filter(Boolean)
  const last = segments[segments.length - 1]
  return last ?? mount
}

export interface DiskCardProps {
  disks?: SystemStatus['disks']
}

export function DiskCard({ disks }: DiskCardProps) {
  const items = disks ?? []
  if (items.length === 0) {
    return <p className="text-xs text-[var(--qm-text-muted)]">无磁盘数据</p>
  }
  return (
    <div className="flex flex-col gap-3">
      {items.map((disk) => {
        const total = disk.totalBytes ?? 0
        const used = disk.usedBytes ?? 0
        const ratio = total > 0 ? used / total : 0
        return (
          <MetricBar
            key={disk.mount ?? '?'}
            label={mountShortName(disk.mount)}
            valueText={`${formatBytes(used)} / ${formatBytes(total)}`}
            ratio={ratio}
            subText={`已用 ${Math.round(ratio * 100)}%`}
          />
        )
      })}
    </div>
  )
}
