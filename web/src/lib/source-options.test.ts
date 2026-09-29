import { describe, expect, it } from 'vitest'
import { individualSourceWords } from './source-options'

describe('individualSourceWords（来源建议拆词提取）', () => {
  it('组合条目拆词去重，单独词保留首现序', () => {
    expect(
      individualSourceWords(['kemono', 'kemono  小红车', '小红车  kemono', 'x', 'hanime1  小红车', '小红车']),
    ).toEqual(['kemono', '小红车', 'x', 'hanime1'])
  })

  it('单空格与多空白分隔同样拆词', () => {
    expect(individualSourceWords(['r34  kemono', '小红车\ti站'])).toEqual(['r34', 'kemono', '小红车', 'i站'])
  })

  it('空串与纯空白条目不产出词', () => {
    expect(individualSourceWords(['', '  ', 'kemono'])).toEqual(['kemono'])
  })

  it('空词表返回空数组', () => {
    expect(individualSourceWords([])).toEqual([])
  })

  it('真实出厂词表全形态拆出七个平台词', () => {
    const vocab = [
      'kemono', '小红车', 'kemono  小红车', 'kemono  小红车  老王论坛', 'x',
      '小红车  kemono', 'hanime1  小红车', 'r34  kemono', '小红车  i站',
    ]
    expect(individualSourceWords(vocab)).toEqual(['kemono', '小红车', '老王论坛', 'x', 'hanime1', 'r34', 'i站'])
  })
})
