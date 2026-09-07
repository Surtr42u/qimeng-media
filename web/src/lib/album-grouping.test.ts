import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AssetSummary } from '@/api/generated'
import { groupAlbumsByDate } from './album-grouping'

// 固定时钟（本地 2026-09-07 周一）：分组键 dateLabel 依赖当前时间；
// 断言用相对天数构造，用例后恢复真实时钟（自清理）。
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-07T12:00:00'))
})
afterEach(() => {
  vi.useRealTimers()
})

/** 相对固定今天偏移 N 天的 ISO 串（本地日历日中午，规避跨日时区偏移） */
function isoFromToday(days: number): string {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() + days, 12).toISOString()
}

function asset(id: string, modifiedAt?: string): AssetSummary {
  return { id, modifiedAt }
}

describe('groupAlbumsByDate', () => {
  it('空数组 → 空分组', () => {
    expect(groupAlbumsByDate([])).toEqual([])
  })

  it('单组：同日资产归并同组，组内保持输入原序', () => {
    const groups = groupAlbumsByDate([asset('a', isoFromToday(0)), asset('b', isoFromToday(0))])
    expect(groups).toHaveLength(1)
    expect(groups[0].label).toBe('今天')
    expect(groups[0].assets.map((a) => a.id)).toEqual(['a', 'b'])
  })

  it('多组按组首 modifiedAt 降序（新 → 旧），与输入顺序无关', () => {
    const groups = groupAlbumsByDate([
      asset('old', isoFromToday(-30)),
      asset('new', isoFromToday(0)),
      asset('mid', isoFromToday(-3)),
    ])
    expect(groups.map((g) => g.label)).toEqual(['今天', '周五', '2026-08-08'])
  })

  it('无日期组殿后：modifiedAt 缺失与非法同落空 label 桶', () => {
    const groups = groupAlbumsByDate([
      asset('nodate'),
      asset('bad', 'not-a-date'),
      asset('new', isoFromToday(0)),
    ])
    expect(groups.map((g) => g.label)).toEqual(['今天', ''])
    expect(groups[1].assets.map((a) => a.id)).toEqual(['nodate', 'bad'])
  })
})
