/**
 * 格式化工具：展示层纯函数（无 IO）。
 * 所有中文文案常量在此维护——组件禁止散落字面量（代码卫生约束）。
 */

/** 今天/昨天/未知日期 文案 */
const TEXT_TODAY = '今天'
const TEXT_YESTERDAY = '昨天'
const TEXT_UNKNOWN_DATE = '未知日期'

/** 周中文名（数组下标与 Date.getDay() 对齐：0=周日） */
const CHINESE_WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const

/** 字节单位（1024 进制，磁盘/媒体大小惯例） */
const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

/** 字节数 → 人类可读（如 "1.2 GB"）；非法输入返回 "--" */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '--'
  let value = bytes
  let unitIndex = 0
  while (value >= 1024 && unitIndex < BYTE_UNITS.length - 1) {
    value /= 1024
    unitIndex++
  }
  // 整数 B 不带小数，其余保留一位；保留一位后整数的（如 2.0GB）也去掉 .0 更简洁
  const text = value >= 100 ? Math.round(value).toString() : value.toFixed(1).replace(/\.0$/, '')
  return `${text} ${BYTE_UNITS[unitIndex]}`
}

/** 时长（ms）→ "m:ss"（<1h）或 "h:mm:ss"；非法输入返回 "--" */
export function formatDuration(durationMs: number | null | undefined): string {
  if (durationMs == null || !Number.isFinite(durationMs) || durationMs < 0) return '--'
  const totalSeconds = Math.round(durationMs / MS_PER_SECOND)
  const hours = Math.floor(totalSeconds / (MINUTES_PER_HOUR * SECONDS_PER_MINUTE))
  const minutes = Math.floor((totalSeconds % (MINUTES_PER_HOUR * SECONDS_PER_MINUTE)) / SECONDS_PER_MINUTE)
  const seconds = totalSeconds % SECONDS_PER_MINUTE
  const pad = (n: number) => n.toString().padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`
}

/** ISO 时间 → 本地日期时间（"2026-08-29 14:05"）；非法输入返回 "--" */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '--'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '--'
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 时间单位换算（共同来源，避免 60/1000 散落） */
const MS_PER_SECOND = 1000
const SECONDS_PER_MINUTE = 60
const MINUTES_PER_HOUR = 60
const HOURS_PER_DAY = 24
const DAY_MS = HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MS_PER_SECOND

/** 按天取整（本地时区当天 0 点），避免跨 24 点后"昨天/今天"判断漂移 */
function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/**
 * 日期分组标签：媒体列表按日期分组（DOMAIN_RULES §8）的组头。
 * 规则：今天="今天"，昨天="昨天"，距今天 2~6 天显示"周X"（本周可读性优先），
 * 更早/更晚显示 "yyyy-MM-dd"，非法输入返回"未知日期"。
 */
export function dateGroupLabel(dateStr: string | null | undefined): string {
  if (!dateStr) return TEXT_UNKNOWN_DATE
  const date = new Date(dateStr)
  if (Number.isNaN(date.getTime())) return TEXT_UNKNOWN_DATE

  const dayDiff = Math.round((startOfDay(new Date()) - startOfDay(date)) / DAY_MS)
  if (dayDiff <= 0) return TEXT_TODAY
  if (dayDiff === 1) return TEXT_YESTERDAY
  if (dayDiff <= 6) return CHINESE_WEEKDAYS[date.getDay()]
  return `${date.getFullYear()}-${(date.getMonth() + 1).toString().padStart(2, '0')}-${date.getDate().toString().padStart(2, '0')}`
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 7 天前起显示日期 */
export function relativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return '--'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '--'
  const diffMs = now.getTime() - date.getTime()
  const minutes = Math.floor(diffMs / (SECONDS_PER_MINUTE * MS_PER_SECOND))
  if (minutes < 1) return '刚刚'
  const hours = Math.floor(minutes / MINUTES_PER_HOUR)
  if (hours < 1) return `${minutes} 分钟前`
  const days = Math.floor(hours / HOURS_PER_DAY)
  if (days < 1) return `${hours} 小时前`
  if (days <= 6) return `${days} 天前`
  // 超过一周：具体日期（挂起上下文里"X 天前"已不可读）
  return formatDateTime(iso).slice(0, 10)
}
