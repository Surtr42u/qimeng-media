import { describe, expect, it } from 'vitest'
import { PLAYS_TO_RANGE, SIZE_TO_RANGE } from './search-mapping'

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
