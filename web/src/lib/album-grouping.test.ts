import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AssetSummary } from '@/api/generated'
import { groupAlbumsByDate } from './album-grouping'
import { localDayKey } from './format'

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

  // dateCounts（服务端按本地日精确计数，协议 2026-10-10 加）：分页只加载前
  // 若干条时组头数字不得用已加载条数冒充（真机实测缺陷：今天 120→125、
  // 周三 66→282 随滚动跳增）。
  it('未给 dateCounts 时 total 回退已加载条数', () => {
    const groups = groupAlbumsByDate([asset('a', isoFromToday(0)), asset('b', isoFromToday(0))])
    expect(groups[0].total).toBe(2)
  })

  it('dateCounts 命中时 total 用服务端真实总数（组内仍只含已加载条数）', () => {
    const today = isoFromToday(0)
    const counts = new Map([[localDayKey(today), 125]])
    const groups = groupAlbumsByDate([asset('a', today), asset('b', today)], counts)
    expect(groups[0].label).toBe('今天')
    expect(groups[0].total).toBe(125)
    expect(groups[0].assets).toHaveLength(2)
  })

  it('dateCounts 未命中的日与无日期桶回退已加载条数', () => {
    const counts = new Map([[localDayKey(isoFromToday(0)), 999]])
    const groups = groupAlbumsByDate(
      [asset('a', isoFromToday(-30)), asset('b', isoFromToday(-30)), asset('nodate')],
      counts,
    )
    expect(groups.map((g) => [g.label, g.total])).toEqual([
      ['2026-08-08', 2],
      ['', 1],
    ])
  })

  it('dateCounts 只改 total 不改分组归属与组内原序', () => {
    const counts = new Map([
      [localDayKey(isoFromToday(0)), 40],
      [localDayKey(isoFromToday(-1)), 7],
    ])
    const groups = groupAlbumsByDate(
      [asset('x1', isoFromToday(0)), asset('y1', isoFromToday(-1)), asset('x2', isoFromToday(0))],
      counts,
    )
    expect(groups.map((g) => [g.label, g.total])).toEqual([
      ['今天', 40],
      ['昨天', 7],
    ])
    expect(groups[0].assets.map((a) => a.id)).toEqual(['x1', 'x2'])
  })

  it('固定偏移口径：夏令时区域的历史日期必须与分桶键同源', () => {
    // 2026-07-01T04:30:00Z：请求时带的是请求时刻的偏移（EST，−300）→ 服务端按
    // −300 分桶得 2026-06-30。客户端若按该日期当时的历史规则（EDT，−240）算键，
    // 会算成 2026-07-01 → 查不到分桶、回退已加载条数 = 原缺陷在夏令时区复现。
    const iso = '2026-07-01T04:30:00.000Z'
    const items = [asset('a', iso), asset('b', iso)]
    const counts = new Map([['2026-06-30', 5]])

    // 同源（−300）：命中服务端分桶 → 组头用真实总数
    expect(groupAlbumsByDate(items, counts, -300)[0].total).toBe(5)
    // 劈叉（−240）：键变 2026-07-01，查不到 → 回退已加载条数（复现面钉死）
    expect(groupAlbumsByDate(items, counts, -240)[0].total).toBe(2)
  })
})
