import { describe, it, expect } from 'vitest'
import { requireStatsOverview, requireStatsTrends, STATS_PROTOCOL_MESSAGE } from './stats-contract'
import type { StatsOverview } from '@/api/generated'

const zero = { viewCount: 0, playCount: 0, seconds: 0 }
const types = { image: zero, video: zero, animated_image: zero }
const sources = { normal: zero, cos: zero }
const overview: StatsOverview = { stillImageCount: 5, animatedImageCount: 2, viewedFilesCount: 70,
  viewedAuthorsCount: 40, viewedTagsCount: 80, windowTotals: zero, mediaTypeWindowTotals: types, sourceWindowTotals: sources }

describe('服务端统计契约', () => {
  it('保留精确数与真实零值，不从榜单或旧 imageCount 反推', () => {
    const report = requireStatsOverview(overview)
    expect(report.animatedImageCount).toBe(2)
    expect(report.viewedFilesCount).toBe(70)
    expect(report.windowTotals.viewCount).toBe(0)
  })
  it('旧服务端缺字段必须报告兼容错误，不能归零', () => {
    expect(() => requireStatsOverview({ imageCount: 7, totalFiles: 10 })).toThrow(STATS_PROTOCOL_MESSAGE)
    expect(() => requireStatsOverview({ ...overview, sourceWindowTotals: { normal: zero } })).toThrow(STATS_PROTOCOL_MESSAGE)
  })
  it('一个响应保留真实桶身份和各维度补零，空响应为成功空态', () => {
    const buckets = [{ start: '2026-10-21', end: '2026-10-21', mediaTypes: types, sources }]
    expect(requireStatsTrends(buckets)).toBe(buckets)
    expect(requireStatsTrends([])).toEqual([])
    expect(() => requireStatsTrends([{ label: '10/21', viewCount: 1 }])).toThrow(STATS_PROTOCOL_MESSAGE)
  })
})
