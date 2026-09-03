/**
 * 展示格式化工具（卡片/详情页共用；纯函数）。
 */

import { LOCALE_ZH } from './constants'

/** 毫秒时长 → mm:ss（超一小时 h:mm:ss）；与卡片时长角标/详情信息共用 */
export function formatDuration(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** ISO 日期 → 卡片短日期「M-D」（与原型卡片 meta 口径一致） */
export function formatShortDate(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1}-${d.getDate()}`
}

/** 字节 → 人类可读大小（回收站/详情信息共用） */
export function formatBytes(n: number): string {
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(1)} GB`
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(1)} MB`
  if (n >= 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${n} B`
}

/** 展示计数（排行角标/统计数字）：万以上压缩为 x.x万（截尾 .0），其余千分位分组 */
export function formatCount(n?: number | null): string {
  if (n === undefined || n === null || Number.isNaN(n)) return '0'
  if (n >= 10000) {
    const w = n / 10000
    const s = w >= 100 ? String(Math.round(w)) : w.toFixed(1).replace(/\.0$/, '')
    return `${s}万`
  }
  return n.toLocaleString(LOCALE_ZH)
}

/** Unix 毫秒时间戳 → 本地「M-D HH:mm」（历史卡观看时间；月份不补零，与 formatShortDate 同口径） */
export function formatDateTime(ts?: number | null): string {
  if (ts === undefined || ts === null || ts <= 0) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${d.getMonth() + 1}-${d.getDate()} ${hh}:${mm}`
}

/** 本地日期键（y-m-d 数值串）：历史「今天/昨天/更早」按天分组用 */
export function localDateKey(ts?: number | null): string {
  if (ts === undefined || ts === null || ts <= 0) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
}
