import { describe, expect, it } from 'vitest'
import { lengthCursorNext, NEXT_OFFSET_HEADER, offsetPage, offsetPageItems } from './pagination'

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

describe('权威 offset 分页', () => {
  it('中间空页仍可续页，满末页按 -1 停止', () => {
    const pages = [
      offsetPage(['first'], new Headers({ [NEXT_OFFSET_HEADER]: '1' }), 0, 1),
      offsetPage([], new Headers({ [NEXT_OFFSET_HEADER]: '2' }), 1, 1),
      offsetPage(['tail'], new Headers({ [NEXT_OFFSET_HEADER]: '-1' }), 2, 1),
    ]
    expect(pages.map((page) => page.nextOffset)).toEqual([1, 2, undefined])
    expect(offsetPageItems({ pages, pageParams: [0, 1, 2] }).pages.flat()).toEqual(['first', 'tail'])
  })

  it('旧服务端无头才回退页长，不能让 -1 回退成还有下一页', () => {
    expect(offsetPage(['a'], new Headers(), 0, 1).nextOffset).toBe(1)
    expect(offsetPage([], undefined, 0, 1).nextOffset).toBeUndefined()
    expect(offsetPage(['a'], new Headers({ [NEXT_OFFSET_HEADER]: '-1' }), 0, 1).nextOffset).toBeUndefined()
  })

  it.each(['', 'bad', '0', '1', '-2', '1.5', '9007199254740992'])('拒绝不前进或非法的续页头 %s', (value) => {
    expect(() => offsetPage([], new Headers({ [NEXT_OFFSET_HEADER]: value }), 1, 1)).toThrow()
  })
})
