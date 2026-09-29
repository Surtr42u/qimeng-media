import { describe, expect, it } from 'vitest'
import { individualSourceWords } from './source-options'

describe('individualSourceWords（来源建议只出单独词）', () => {
  it('过滤组合条目（含空白），单独词原序保留', () => {
    expect(individualSourceWords(['kemono', 'kemono  小红车', '小红车  kemono', 'x', 'hanime1  小红车', '小红车'])).toEqual([
      'kemono',
      'x',
      '小红车',
    ])
  })

  it('单空格分隔同样视为组合', () => {
    expect(individualSourceWords(['老王论坛 合集', '老王论坛'])).toEqual(['老王论坛'])
  })

  it('空串与纯空白条目被过滤', () => {
    expect(individualSourceWords(['', '  ', 'kemono'])).toEqual(['kemono'])
  })

  it('空词表返回空数组', () => {
    expect(individualSourceWords([])).toEqual([])
  })
})
