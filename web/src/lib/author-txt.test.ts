import { describe, expect, it } from 'vitest'
import { AUTHOR_TXT_REQUEST_MAX_BYTES, validateAuthorTxtFile, validateAuthorTxtRequest } from './author-txt'

describe('作者 TXT 的真实 JSON 字节限制', () => {
  it('读取前拒绝错误扩展名与超大文件', () => {
    expect(() => validateAuthorTxtFile(new File(['x'], 'a.bin'))).toThrow('仅支持')
    expect(() => validateAuthorTxtFile(new File([new Uint8Array(AUTHOR_TXT_REQUEST_MAX_BYTES + 1)], 'a.txt'))).toThrow('1 MiB')
    expect(() => validateAuthorTxtFile(new File([], '合成.TXT'))).not.toThrow()
  })
  it('计入信封、UTF-8、转义和冲突选择，恰好上限可发送', () => {
    const body = { filename: '合成.txt', content: '', conflictResolution: 'keep' as const }
    const overhead = new TextEncoder().encode(JSON.stringify(body)).byteLength
    body.content = 'x'.repeat(AUTHOR_TXT_REQUEST_MAX_BYTES - overhead)
    expect(() => validateAuthorTxtRequest(body)).not.toThrow()
    expect(() => validateAuthorTxtRequest({ ...body, content: body.content + 'x' })).toThrow('1 MiB')
    expect(() => validateAuthorTxtRequest({ content: '\n'.repeat(AUTHOR_TXT_REQUEST_MAX_BYTES / 2) })).toThrow('1 MiB')
  })
})
