import { describe, expect, it, vi } from 'vitest'

// api-client.ts 在模块顶层读 localStorage（token 缓存，浏览器专用全局），
// node 测试环境无此全局，静态 import ./sse 会在模块求值期 ReferenceError。
// 故先打桩再动态 import——在 sse.ts（及其 import 链）求值之前生效；
// 测试不改动源码本体（ADR-0017 范围约束）。
vi.stubGlobal('localStorage', {
  getItem: (): string | null => null,
  setItem: () => {},
  removeItem: () => {},
})

const { createSSEParser } = await import('./sse')

describe('createSSEParser', () => {
  it('跨 chunk 帧边界：帧拆进两个 chunk，拼齐后才产出', () => {
    const parse = createSSEParser()
    expect(parse('event: scan.progress\ndata: {"per')).toEqual([])
    expect(parse('cent":50}\n\n')).toEqual([{ event: 'scan.progress', data: '{"percent":50}' }])
  })

  it('跨 chunk 行边界：前 chunk 末尾半行留给下个 chunk 不丢字', () => {
    const parse = createSSEParser()
    expect(parse('data: hel')).toEqual([])
    expect(parse('lo\n\n')).toEqual([{ event: 'message', data: 'hello' }])
  })

  it('一个 chunk 多帧：各帧独立产出', () => {
    const parse = createSSEParser()
    expect(parse('event: a\ndata: 1\n\nevent: b\ndata: 2\n\n')).toEqual([
      { event: 'a', data: '1' },
      { event: 'b', data: '2' },
    ])
  })

  it('缺 event 字段：事件名回退 message（WHATWG 默认）', () => {
    const parse = createSSEParser()
    expect(parse('data: hi\n\n')).toEqual([{ event: 'message', data: 'hi' }])
  })

  it('缺 data 字段：仅 event 的帧 data 为空串，仍产出', () => {
    const parse = createSSEParser()
    expect(parse('event: ping\n\n')).toEqual([{ event: 'ping', data: '' }])
  })

  it('event/data 全缺（纯注释/纯 id 帧）不产出：心跳容错', () => {
    const parse = createSSEParser()
    expect(parse(': keepalive\nid: 3\n\n')).toEqual([])
  })

  it('多行 data 按 \\n 拼接；CRLF 行尾剥离', () => {
    const parse = createSSEParser()
    expect(parse('data: a\r\ndata: b\r\n\r\n')).toEqual([{ event: 'message', data: 'a\nb' }])
  })

  it('id/retry 随帧携带；retry 仅收正有限数；未知字段忽略', () => {
    const parse = createSSEParser()
    expect(parse('id: 7\nretry: 3000\nx-unknown: y\ndata: z\n\n')).toEqual([
      { event: 'message', data: 'z', id: '7', retry: 3000 },
    ])
    expect(parse('retry: 0\nretry: -5\nretry: abc\ndata: w\n\n')).toEqual([
      { event: 'message', data: 'w' },
    ])
  })
})
