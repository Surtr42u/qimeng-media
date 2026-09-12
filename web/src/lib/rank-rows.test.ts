import { describe, expect, it } from 'vitest'
import { authorRankRows, tagRankRows } from './rank-rows'
import type { Author, Tag } from '@/api/generated'

/** 生成类型只以字面子集断言（测试关心 fileCount/viewCount/displayName/type） */
const tag = (name: string, fileCount?: number): Tag => ({ name, fileCount }) as Tag
const author = (
  displayName: string, viewCount: number, fileCount: number, type?: string,
): Author => ({ displayName, viewCount, fileCount, type }) as Author

describe('tagRankRows', () => {
  it('按 fileCount 降序，行计数 = 关联文件数', () => {
    const rows = tagRankRows([tag('b', 3), tag('a', 10), tag('c', 1)])
    expect(rows).toEqual([
      { name: 'a', count: '10' },
      { name: 'b', count: '3' },
      { name: 'c', count: '1' },
    ])
  })

  it('fileCount 缺省按 0 参与排序（容错口径不丢行）', () => {
    const rows = tagRankRows([tag('x'), tag('y', 2)])
    expect(rows).toEqual([
      { name: 'y', count: '2' },
      { name: 'x', count: '0' },
    ])
  })

  it('不改入参（slice 后排序，调用方数组原序保持）', () => {
    const input = [tag('a', 1), tag('b', 9)]
    tagRankRows(input)
    expect(input[0].name).toBe('a')
  })
})

describe('authorRankRows', () => {
  it('常看作者口径：0 浏览不入榜，按浏览数降序', () => {
    const rows = authorRankRows([
      author('甲', 0, 100), author('乙', 5, 2), author('丙', 20, 7),
    ])
    expect(rows.map((r) => r.name)).toEqual(['丙', '乙'])
  })

  it('F7 计数口径：count 置空串（不渲染裸数字），副标题「N 个文件」', () => {
    const rows = authorRankRows([author('乙', 5, 2)])
    expect(rows[0]).toEqual({ name: '乙', count: '', sub: '2 个文件' })
  })

  it('COS 作者显示名追加「 ·COS」标识（authorDisplayName 同口径）', () => {
    const rows = authorRankRows([author('丁', 3, 4, 'cos')])
    expect(rows[0].name).toBe('丁 ·COS')
  })
})
