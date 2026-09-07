import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dateLabel, formatBytes, formatCount } from './format'

// 固定时钟：本地 2026-09-07（周一）12:00——日期串无时区后缀按本地时区解析，
// 断言全部用「相对今天 N 天」的本地日历日构造，不依赖运行环境时区。
// 每条用例后恢复真实时钟（自清理），避免污染同文件其他断言。
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

describe('dateLabel', () => {
  it('今天 / 昨天', () => {
    expect(dateLabel(isoFromToday(0))).toBe('今天')
    expect(dateLabel(isoFromToday(-1))).toBe('昨天')
  })

  it('2~6 天前 → 周X（本地 2026-09-07 周一：-3=周五、-6=周二）', () => {
    expect(dateLabel(isoFromToday(-3))).toBe('周五')
    expect(dateLabel(isoFromToday(-6))).toBe('周二')
  })

  it('更早 → yyyy-MM-dd（7 天前为分档边界）；未来日期同落此档', () => {
    expect(dateLabel(isoFromToday(-7))).toBe('2026-08-31')
    expect(dateLabel(isoFromToday(-30))).toBe('2026-08-08')
    expect(dateLabel(isoFromToday(1))).toBe('2026-09-08')
  })

  it('空值 / 非法日期 → 空串（调用方不渲染组头）', () => {
    expect(dateLabel(undefined)).toBe('')
    expect(dateLabel(null)).toBe('')
    expect(dateLabel('')).toBe('')
    expect(dateLabel('not-a-date')).toBe('')
  })
})

describe('formatBytes', () => {
  it('B 档：不足 1KB 原样数字', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
  })

  it('KB 档：整数取整、进位边界', () => {
    expect(formatBytes(1024)).toBe('1 KB')
    expect(formatBytes(1536)).toBe('2 KB') // 1.5 KB → toFixed(0) 四舍五入
    expect(formatBytes(1024 ** 2 - 1)).toBe('1024 KB') // KB 档上限边界
  })

  it('MB / GB 档：一位小数（含 .0）', () => {
    expect(formatBytes(1024 ** 2)).toBe('1.0 MB')
    expect(formatBytes(1.5 * 1024 ** 2)).toBe('1.5 MB')
    expect(formatBytes(1024 ** 3)).toBe('1.0 GB')
    expect(formatBytes(2.5 * 1024 ** 3)).toBe('2.5 GB')
  })
})

describe('formatCount', () => {
  it('空值 / NaN → 回退 "0"', () => {
    expect(formatCount(undefined)).toBe('0')
    expect(formatCount(null)).toBe('0')
    expect(formatCount(Number.NaN)).toBe('0')
  })

  it('万以下：千分位分组', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(9999)).toBe('9,999')
  })

  it('1 万~100 万：一位小数并截尾 .0', () => {
    expect(formatCount(10000)).toBe('1万')
    expect(formatCount(12345)).toBe('1.2万')
    expect(formatCount(99999)).toBe('10万') // 9.9999 → toFixed(1) = '10.0' → 截尾
  })

  it('百万级起：整数万（四舍五入）', () => {
    expect(formatCount(1000000)).toBe('100万')
    expect(formatCount(1234567)).toBe('123万')
  })
})
