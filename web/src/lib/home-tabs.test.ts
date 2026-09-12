import { describe, expect, it } from 'vitest'
import { HOME_TABS, RANK_PERIODS, parseRankPeriod } from './home-tabs'

describe('HOME_TABS / RANK_PERIODS（URL 参数单一事实源）', () => {
  it('三 tab + 四周期，key 即 URL 参数值（改动即接口变更，锁字面）', () => {
    expect(HOME_TABS.map((t) => t.key)).toEqual(['recommend', 'cos', 'hot'])
    expect(RANK_PERIODS.map((p) => p.key)).toEqual(['day', 'month', 'week', 'year'])
  })
})

describe('parseRankPeriod', () => {
  it.each([
    ['day', 'day'],
    ['week', 'week'],
    ['month', 'month'],
    ['year', 'year'],
  ])('合法值 %s 原样通过', (input, expected) => {
    expect(parseRankPeriod(input)).toBe(expected)
  })

  it.each([
    ['非法值回退日榜', 'quarter'],
    ['大小写敏感（协议枚举小写）', 'Week'],
    ['空串回退日榜', ''],
  ])('%s', (_label, input) => {
    expect(parseRankPeriod(input as string)).toBe('day')
  })

  it('null（URL 不带参数）回退日榜——原型每次进排行榜重置日榜', () => {
    expect(parseRankPeriod(null)).toBe('day')
  })
})
