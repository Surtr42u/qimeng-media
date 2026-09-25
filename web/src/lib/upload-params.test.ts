import { describe, expect, it } from 'vitest'
import { buildUploadQuery } from './upload-params'

describe('buildUploadQuery', () => {
  it('只含 libraryId/dir/filename 三个必填项（挂靠三参数退役后唯一形态）', () => {
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
})
