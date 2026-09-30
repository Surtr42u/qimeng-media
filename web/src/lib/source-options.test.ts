import { describe, expect, it } from 'vitest'
import { individualSourceWords } from './source-options'

describe('individualSourceWords（来源建议拆词提取）', () => {
  it('组合条目拆词去重，单独词保留首现序', () => {
    expect(
      individualSourceWords(['site-a', 'site-a  site-b', 'site-b  site-a', 'x', 'site-d  site-b', 'site-b']),
    ).toEqual(['site-a', 'site-b', 'x', 'site-d'])
  })

  it('单空格与多空白分隔同样拆词', () => {
    expect(individualSourceWords(['site-f  site-a', 'site-b\tsite-g'])).toEqual(['site-f', 'site-a', 'site-b', 'site-g'])
  })

  it('空串与纯空白条目不产出词', () => {
    expect(individualSourceWords(['', '  ', 'site-a'])).toEqual(['site-a'])
  })

  it('空词表返回空数组', () => {
    expect(individualSourceWords([])).toEqual([])
  })

  it('真实出厂词表全形态拆出七个平台词', () => {
    const vocab = [
      'site-a', 'site-b', 'site-a  site-b', 'site-a  site-b  forum-c', 'x',
      'site-b  site-a', 'site-d  site-b', 'site-f  site-a', 'site-b  site-g',
    ]
    expect(individualSourceWords(vocab)).toEqual(['site-a', 'site-b', 'forum-c', 'x', 'site-d', 'site-f', 'site-g'])
  })
})
