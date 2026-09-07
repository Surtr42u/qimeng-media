import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  SSE_MAX_RECONNECT_DELAY_MS,
  SSE_MIN_RECONNECT_DELAY_MS,
  SSE_RECONNECT_DELAY_MS,
} from './constants'

// api-client.ts 在模块顶层读 localStorage（token 缓存，浏览器专用全局），
// node 测试环境无此全局，静态 import ./sse 会在模块求值期 ReferenceError。
// 故先打桩再动态 import——在 sse.ts（及其 import 链）求值之前生效；
// 测试不改动源码本体（ADR-0017 范围约束）。
vi.stubGlobal('localStorage', {
  getItem: (): string | null => null,
  setItem: () => {},
  removeItem: () => {},
})

const { createSSEParser, subscribeSSE } = await import('./sse')
// 与 sse.ts 内部引用同一 api-client 模块实例：用 onAuthFailed 注册监听即可
// 观测 emitAuthFailed 广播，无需模块 mock（本文件禁止 unstubAllGlobals——
// 会连 localStorage 桩一起拆掉，后续 connect 取 headers 时 ReferenceError）
const { onAuthFailed } = await import('./api-client')

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

/** 构造 subscribeSSE 可消费的流式 Response 桩：逐条吐 chunks（UTF-8 编码）后正常关流 */
function streamResponse(chunks: string[]): Response {
  const encoder = new TextEncoder()
  let i = 0
  const reader = {
    read: async (): Promise<{ done: boolean; value?: Uint8Array }> =>
      i < chunks.length ? { done: false, value: encoder.encode(chunks[i++]) } : { done: true },
  }
  return { ok: true, status: 200, body: { getReader: () => reader } } as unknown as Response
}

describe('subscribeSSE', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('retry 帧低于下限：重连延迟夹到 SSE_MIN_RECONNECT_DELAY_MS', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn(async () => streamResponse(['retry: 500\ndata: x\n\n']))
    vi.stubGlobal('fetch', fetchMock)
    const cancel = subscribeSSE({ url: '/api/v1/events', onMessage: () => {} })
    await vi.advanceTimersByTimeAsync(0) // 冲微任务：首轮读完流即断，按夹取值安排重连定时器
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(SSE_MIN_RECONNECT_DELAY_MS - 1)
    expect(fetchMock).toHaveBeenCalledTimes(1) // 下限之内不提前重连
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(2) // 恰在 1000ms 重连
    cancel()
  })

  it('retry 帧超上限：重连延迟夹到 SSE_MAX_RECONNECT_DELAY_MS', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn(async () => streamResponse(['retry: 60000\ndata: x\n\n']))
    vi.stubGlobal('fetch', fetchMock)
    const cancel = subscribeSSE({ url: '/api/v1/events', onMessage: () => {} })
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(SSE_MAX_RECONNECT_DELAY_MS - 1)
    expect(fetchMock).toHaveBeenCalledTimes(1) // 上限之内不提前重连
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(2) // 恰在 30000ms 重连
    cancel()
  })

  it('retry 帧为区间内正常值：直用不夹取', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn(async () => streamResponse(['retry: 5000\ndata: x\n\n']))
    vi.stubGlobal('fetch', fetchMock)
    const cancel = subscribeSSE({ url: '/api/v1/events', onMessage: () => {} })
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(4999)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(2) // 恰在帧指定的 5000ms 重连
    cancel()
  })

  it('断流无 retry 帧：用默认 SSE_RECONNECT_DELAY_MS', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn(async () => streamResponse(['data: x\n\n']))
    vi.stubGlobal('fetch', fetchMock)
    const cancel = subscribeSSE({ url: '/api/v1/events', onMessage: () => {} })
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(SSE_RECONNECT_DELAY_MS - 1)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(2) // 恰在默认 3000ms 重连
    cancel()
  })

  it('HTTP 401/403：广播鉴权失败 + onError 报错，连接终止不再重试', async () => {
    vi.useFakeTimers()
    for (const status of [401, 403]) {
      const authFailed = vi.fn()
      const off = onAuthFailed(authFailed)
      const fetchMock = vi.fn(async () => ({ ok: false, status }) as unknown as Response)
      vi.stubGlobal('fetch', fetchMock)
      const onError = vi.fn()
      const cancel = subscribeSSE({ url: '/api/v1/events', onMessage: () => {}, onError })
      await vi.advanceTimersByTimeAsync(0)
      expect(authFailed).toHaveBeenCalledTimes(1) // emitAuthFailed 广播（AuthGate 接管入口）
      expect(onError).toHaveBeenCalledTimes(1)
      expect((onError.mock.calls[0][0] as Error).message).toContain(`HTTP ${status}`)
      await vi.advanceTimersByTimeAsync(SSE_MAX_RECONNECT_DELAY_MS * 2)
      expect(fetchMock).toHaveBeenCalledTimes(1) // token 已失效：停止重连不空转
      off()
      cancel()
    }
  })
})
