/**
 * 运行卡：uptime（天/时/分）× 版本。
 */

/** 时间换算单位（与 lib/format.ts 同进制，本处用于"段数"格式，不引用其私有常量） */
const SECONDS_PER_MINUTE = 60
const SECONDS_PER_HOUR = 3600
const SECONDS_PER_DAY = 86_400

/** uptime 秒 → "N 天 N 小时 N 分钟"（天/时为零省略对应段；非法输入返回 "--"） */
export function formatUptime(uptimeSeconds: number | undefined): string {
  if (uptimeSeconds == null || !Number.isFinite(uptimeSeconds) || uptimeSeconds < 0) return '--'
  const seconds = Math.floor(uptimeSeconds)
  const days = Math.floor(seconds / SECONDS_PER_DAY)
  const hours = Math.floor((seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR)
  const minutes = Math.floor((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)
  const parts: string[] = []
  if (days > 0) parts.push(`${days} 天`)
  if (hours > 0) parts.push(`${hours} 小时`)
  parts.push(`${minutes} 分钟`)
  return parts.join(' ')
}

export interface UptimeCardProps {
  uptimeSeconds?: number
  version?: string
}

export function UptimeCard({ uptimeSeconds, version }: UptimeCardProps) {
  return (
    <div className="flex flex-col gap-2">
      <div>
        <p className="text-xs text-[var(--qm-text-muted)]">已运行</p>
        <p className="font-mono text-lg font-semibold">{formatUptime(uptimeSeconds)}</p>
      </div>
      <p className="text-xs text-[var(--qm-text-muted)]">
        版本 <span className="font-mono text-foreground">{version ?? '--'}</span>
      </p>
    </div>
  )
}
