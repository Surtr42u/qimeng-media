import type { HistoryItem } from '@/api/generated'
import { localDateKey } from '@/lib/format'

/**
 * 浏览历史「今天/昨天/更早」分组（2026-09-07 自 MinePage 抽离——ADR-0008 业务
 * 规则不内嵌页面组件）。
 * 为什么用本地日历日键分组而非时间差：跨零点后「今天/昨天」必须整体换组，按
 * 日键比较可让下次渲染自动迁移；无效时间戳（<=0）剔除——无观看时间的脏数据
 * 不进任何组。组内按 lastViewedAt 倒序（最近看的在前）。query 为标题子串
 * 过滤（fileName，大小写不敏感；空串 = 不过滤）。纯函数：不碰 IO。
 */

/** 历史分组（顺序固定：今天 → 昨天 → 更早；空组由调用方或本函数过滤隐藏） */
const HIST_GROUPS = [
  { key: 'today', label: '今天' },
  { key: 'yesterday', label: '昨天' },
  { key: 'earlier', label: '更早' },
] as const

type HistGroupKey = (typeof HIST_GROUPS)[number]['key']

/** 本地今天/昨天日期键（分组边界；每次调用现算，跨零点后下次渲染自动更新） */
function dayBoundaryKeys(): { today: string; yesterday: string } {
  const now = new Date()
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  return { today: localDateKey(now.getTime()), yesterday: localDateKey(yesterday.getTime()) }
}

function histGroupOf(ts: number, today: string, yesterday: string): HistGroupKey {
  const k = localDateKey(ts)
  if (k === today) return 'today'
  if (k === yesterday) return 'yesterday'
  return 'earlier'
}

export interface HistoryGroup {
  key: HistGroupKey
  label: string
  items: HistoryItem[]
}

/** 过滤（时间有效 + 标题子串）→ 分三组 → 组内倒序 → 隐藏空组 */
export function groupHistory(items: HistoryItem[], query: string): HistoryGroup[] {
  const q = query.trim().toLowerCase()
  const { today, yesterday } = dayBoundaryKeys()
  const matched = items.filter(
    (h) =>
      (h.lastViewedAt ?? 0) > 0 &&
      (!q || (h.fileName ?? '').toLowerCase().includes(q)),
  )
  return HIST_GROUPS.map((g) => ({
    ...g,
    items: matched
      .filter((h) => histGroupOf(h.lastViewedAt as number, today, yesterday) === g.key)
      .sort((a, b) => (b.lastViewedAt ?? 0) - (a.lastViewedAt ?? 0)),
  })).filter((g) => g.items.length > 0)
}
