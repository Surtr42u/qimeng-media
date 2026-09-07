import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoryItem } from '@/api/generated'
import { groupHistory } from './history-grouping'

// 固定时钟（本地 2026-09-07 周一）：今天/昨天边界按本地日历日键现算；
// 时间戳用本地时区构造相对天数，用例后恢复真实时钟（自清理）。
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-07T12:00:00'))
})
afterEach(() => {
  vi.useRealTimers()
})

/** 相对固定今天往前 N 天的本地 hour 点整的时间戳（Unix 毫秒） */
function at(daysAgo: number, hour: number): number {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() - daysAgo, hour).getTime()
}

function item(id: string, lastViewedAt?: number, fileName?: string): HistoryItem {
  return { id, lastViewedAt, fileName: fileName ?? `file-${id}.jpg` }
}

describe('groupHistory', () => {
  it('跨日分组：今天→昨天→更早顺序固定；跨零点边界各归各组；组内倒序', () => {
    const groups = groupHistory(
      [item('e', at(5, 9)), item('c', at(1, 23)), item('b', at(0, 23)), item('a', at(0, 9))],
      '',
    )
    expect(groups.map((g) => g.label)).toEqual(['今天', '昨天', '更早'])
    expect(groups[0].items.map((h) => h.id)).toEqual(['b', 'a']) // 组内最近看的在前
    expect(groups[1].items.map((h) => h.id)).toEqual(['c']) // 昨天 23 点仍归昨天
    expect(groups[2].items.map((h) => h.id)).toEqual(['e'])
  })

  it('标题子串过滤大小写不敏感（query 前后空白忽略）', () => {
    const groups = groupHistory(
      [item('hit', at(0, 9), 'Summer.JPG'), item('miss', at(0, 10), 'winter.png')],
      '  SUM ',
    )
    expect(groups).toHaveLength(1)
    expect(groups[0].items.map((h) => h.id)).toEqual(['hit'])
  })

  it('时间无效（缺失 / 0 / 负值）剔除，不进任何组', () => {
    const groups = groupHistory(
      [item('bad0', 0), item('none'), item('neg', -5), item('ok', at(0, 8))],
      '',
    )
    expect(groups).toHaveLength(1)
    expect(groups[0].items.map((h) => h.id)).toEqual(['ok'])
  })

  it('空组隐藏：过滤后无命中或输入为空 → 不返回空组', () => {
    expect(groupHistory([item('a', at(0, 9))], '不存在的标题')).toEqual([])
    expect(groupHistory([], '')).toEqual([])
  })
})
