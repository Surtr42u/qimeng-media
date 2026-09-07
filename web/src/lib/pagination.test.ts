import { describe, expect, it } from 'vitest'
import { lengthCursorNext } from './pagination'

describe('lengthCursorNext', () => {
  it('页长 === limit：判有下页，续游标 = lastPageParam + limit', () => {
    const next = lengthCursorNext(10)
    expect(next(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'], [], 0)).toBe(10)
    expect(next(Array.from({ length: 10 }, (_, i) => i), [], 30)).toBe(40)
  })

  it('页长 < limit：判到底，返回 undefined（含空页）', () => {
    const next = lengthCursorNext(10)
    expect(next([1, 2, 3], [], 20)).toBeUndefined()
    expect(next([], [], 0)).toBeUndefined()
  })

  it('页长 > limit（协议超发）：严格等长判据不认满页，返回 undefined', () => {
    const next = lengthCursorNext(10)
    expect(next(Array.from({ length: 11 }, (_, i) => i), [], 10)).toBeUndefined()
  })

  it('续游标随 lastPageParam 递进而递进（limit=5，模拟连续翻页）', () => {
    const next = lengthCursorNext(5)
    expect(next(Array.from({ length: 5 }, (_, i) => i), [], 0)).toBe(5)
    expect(next(Array.from({ length: 5 }, (_, i) => i), [], 5)).toBe(10)
    expect(next([0], [], 10)).toBeUndefined() // 末页不足 → 停止续页
  })
})
