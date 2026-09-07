import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PLAYS_TO_RANGE, SIZE_TO_RANGE, dateRangeFor, partitionKey } from './search-mapping'

describe('PLAYS_TO_RANGE', () => {
  it('播放档映射全表（边界以 browse.sql 为准：none=0/low=1-5/mid=5-20/high=>20）', () => {
    // toStrictEqual：undefined 档（「全部」）必须是显式存在的键
    expect(PLAYS_TO_RANGE).toStrictEqual({
      全部: undefined,
      未播放: 'none',
      '1-5': 'low',
      '5-20': 'mid',
      '>20': 'high',
    })
  })

  it('未知档位 → undefined', () => {
    expect(PLAYS_TO_RANGE['不存在的档']).toBeUndefined()
  })
})

describe('SIZE_TO_RANGE', () => {
  it('大小档映射全表（lt1m<1MB/m1to10<10MB/m10to50<50MB/gt50m）', () => {
    expect(SIZE_TO_RANGE).toStrictEqual({
      全部: undefined,
      '<1MB': 'lt1m',
      '1-10MB': 'm1to10',
      '10-50MB': 'm10to50',
      '>50MB': 'gt50m',
    })
  })

  it('未知档位 → undefined', () => {
    expect(SIZE_TO_RANGE['60MB']).toBeUndefined()
  })
})

describe('dateRangeFor', () => {
  // 固定时钟：2026-09-09（周三）12:00 本地——localDate 取本地日历日，
  // 断言串与时区无关；每条用例后恢复真实时钟（自清理）。
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-09T12:00:00'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('今天：dateFrom = dateTo = 今天（dateTo 含当日全天）', () => {
    expect(dateRangeFor('今天')).toStrictEqual({ dateFrom: '2026-09-09', dateTo: '2026-09-09' })
  })

  it('本周：周一为一周之始（周三固定钟 → 09-07 ~ 09-09）', () => {
    expect(dateRangeFor('本周')).toStrictEqual({ dateFrom: '2026-09-07', dateTo: '2026-09-09' })
  })

  it('本周跨周日：getDay()=0 回退 6 天仍落回同一周一（周日不另起一周）', () => {
    vi.setSystemTime(new Date('2026-09-13T12:00:00')) // 周日
    expect(dateRangeFor('本周')).toStrictEqual({ dateFrom: '2026-09-07', dateTo: '2026-09-13' })
  })

  it('本月 / 近三月 / 本年：dateFrom 各按口径、dateTo 一律含当日', () => {
    expect(dateRangeFor('本月')).toStrictEqual({ dateFrom: '2026-09-01', dateTo: '2026-09-09' })
    expect(dateRangeFor('近三月')).toStrictEqual({ dateFrom: '2026-06-09', dateTo: '2026-09-09' })
    expect(dateRangeFor('本年')).toStrictEqual({ dateFrom: '2026-01-01', dateTo: '2026-09-09' })
  })

  it('未知档 → undefined（「全部」「按年份区间」不产日期，年份走 yearFrom/yearTo）', () => {
    expect(dateRangeFor('全部')).toBeUndefined()
    expect(dateRangeFor('按年份区间')).toBeUndefined()
    expect(dateRangeFor('不存在的档')).toBeUndefined()
  })
})

describe('partitionKey', () => {
  it('三态：COS→cos / 全部→all / 其余一律→regular（大小写敏感，兜底归 regular）', () => {
    expect(partitionKey('COS')).toBe('cos')
    expect(partitionKey('全部')).toBe('all')
    expect(partitionKey('照片')).toBe('regular')
    expect(partitionKey('')).toBe('regular')
    expect(partitionKey('cos')).toBe('regular') // 小写非精确 'COS'：走 regular 兜底
  })
})
