import { describe, expect, it } from 'vitest'
import { authorRankRows, tagRankRows } from './rank-rows'

describe('统计榜格式化', () => {
  it('保持服务端顺序和窗口计数，不从管理列表按文件数重排', () => {
    const rows = [{ authorId: 'a', displayName: '甲', views: 9 }, { authorId: 'b', displayName: '乙', views: 9 }]
    expect(authorRankRows(rows)).toEqual([{ name: '甲', count: '9 次' }, { name: '乙', count: '9 次' }])
    expect(rows.map((row) => row.authorId)).toEqual(['a', 'b'])
    expect(tagRankRows([{ tag: '标签', views: 4 }])).toEqual([{ name: '标签', count: '4 次' }])
  })
  it('真实空榜保持空数组', () => {
    expect(authorRankRows([])).toEqual([])
    expect(tagRankRows([])).toEqual([])
  })
})
