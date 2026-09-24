import { describe, expect, it } from 'vitest'
import { buildUploadQuery } from './upload-params'

describe('buildUploadQuery', () => {
  it('无挂靠：只含 libraryId/dir/filename 三个必填项（与旧上传行为字节一致）', () => {
    const sp = buildUploadQuery('lib1', 'a/b', 'x.jpg')
    expect(sp.toString()).toBe('libraryId=lib1&dir=a%2Fb&filename=x.jpg')
    expect(sp.getAll('source')).toEqual([])
    expect(sp.get('authorId')).toBeNull()
    expect(sp.get('authorName')).toBeNull()
  })

  it('空串目录/文件名照常编码（协议语义：dir 空 = 库根）', () => {
    const sp = buildUploadQuery('lib1', '', 'f.mp4')
    expect(sp.toString()).toBe('libraryId=lib1&dir=&filename=f.mp4')
  })

  it('authorId 挂靠：append authorId，不 append authorName', () => {
    const sp = buildUploadQuery('lib1', '', 'f.jpg', { authorId: 'a1' })
    expect(sp.get('authorId')).toBe('a1')
    expect(sp.get('authorName')).toBeNull()
  })

  it('authorName 挂靠：append authorName（回车新建口径），不 append authorId', () => {
    const sp = buildUploadQuery('lib1', '', 'f.jpg', { authorName: '绮梦' })
    expect(sp.get('authorName')).toBe('绮梦')
    expect(sp.get('authorId')).toBeNull()
  })

  it('authorId 与 authorName 同给（防御）：协议互斥只发 authorId', () => {
    const sp = buildUploadQuery('lib1', '', 'f.jpg', { authorId: 'a1', authorName: '绮梦' })
    expect(sp.get('authorId')).toBe('a1')
    expect(sp.get('authorName')).toBeNull()
  })

  it('source 数组展开：同名 query 重复传多值（与 GET /assets source 数组同款）', () => {
    const sp = buildUploadQuery('lib1', '', 'f.jpg', { authorId: 'a1' }, ['kemono', 'x', 'r34'])
    expect(sp.getAll('source')).toEqual(['kemono', 'x', 'r34'])
  })

  it('无挂靠时 sources 被忽略：协议规定 source 仅指定作者时合法', () => {
    const sp = buildUploadQuery('lib1', '', 'f.jpg', null, ['kemono'])
    expect(sp.getAll('source')).toEqual([])
  })

  it('空串来源词被跳过（输入行 trim 口径的兜底）', () => {
    const sp = buildUploadQuery('lib1', '', 'f.jpg', { authorName: '绮梦' }, ['kemono', ''])
    expect(sp.getAll('source')).toEqual(['kemono'])
  })
})
