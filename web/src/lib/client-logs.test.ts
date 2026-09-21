/**
 * 渲染期异常上报纯函数锁定（审计 R12：AppErrorBoundary → client-logs 旁路）。
 * node 环境无 window/DOM：renderErrorEntry 纯函数直测；reportRenderError
 * 的「绝不外抛」纪律用一个内部必然失败的场景锁定。端到端 flush 行为由
 * 服务端 client-logs 集成面兜底，不在本文件重复。
 */
import { describe, expect, it, vi } from 'vitest'
import { flushClientLogs, renderErrorEntry, reportRenderError } from './client-logs'

// api-client 模块顶层读取 localStorage（token 初始化）——node 测试环境无
// 该全局，必须在模块导入前打桩（vi.hoisted 先于 import 执行）。
vi.hoisted(() => {
  ;(globalThis as Record<string, unknown>).localStorage = { getItem: () => null }
})

describe('renderErrorEntry（渲染异常条目组装）', () => {
  it('message 带 [RenderError] 前缀，level=error，含 JS 堆栈', () => {
    const err = new Error('boom')
    const e = renderErrorEntry(err)
    expect(e.level).toBe('error')
    expect(e.message).toBe('[RenderError] boom')
    expect(e.stack).toContain('boom') // V8 堆栈首行含 message
  })

  it('带组件树栈时追加 Component stack 段（定位抛错组件）', () => {
    const err = new Error('boom')
    const e = renderErrorEntry(err, '    at Div\n    at App')
    expect(e.stack).toContain('Component stack:')
    expect(e.stack).toContain('at App')
  })

  it('componentStack 为 null（React 某些异步边界场景）不追加空段', () => {
    const err = new Error('boom')
    const withNull = renderErrorEntry(err, null)
    expect(withNull.stack).not.toContain('Component stack:')
  })

  it('超长 message 截 2000 字（协议 maxLength，超限服务端整批 400）', () => {
    const err = new Error('x'.repeat(3000))
    const e = renderErrorEntry(err)
    expect(e.message.length).toBe(2000)
    expect(e.message.startsWith('[RenderError] ')).toBe(true)
  })
})

describe('reportRenderError（防递归纪律）', () => {
  it('正常路径入队且 flush 发出（fetch/localStorage 打桩）', async () => {
    const calls: Array<{ body: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: { body: string }) => {
      calls.push({ body: init?.body ?? '' })
      return { ok: true }
    }))
    vi.stubGlobal('localStorage', { getItem: () => null })
    try {
      reportRenderError(new Error('render blew up'), 'at Widget')
      await flushClientLogs()
      expect(calls.length).toBe(1)
      const payload = JSON.parse(calls[0].body) as { events: Array<{ message: string }> }
      expect(payload.events.some((e) => e.message === '[RenderError] render blew up')).toBe(true)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('内部机制异常时绝不外抛（吞掉，与 install 的 handler 同一纪律）', () => {
    // 真实失败路径：renderErrorEntry 组装读 error.message——毒化 getter 在
    // reportRenderError 的 try 内同步抛错，其 catch 必须吞掉。（此前版本打桩
    // localStorage/fetch，但该路径根本不经过它们——断言空转，2026-09-21 维护批改实锁。）
    const poisoned = Object.create(Error.prototype, {
      message: {
        get() {
          throw new Error('message getter broken')
        },
      },
    }) as Error
    expect(() => reportRenderError(poisoned, 'at Widget')).not.toThrow()
  })

  it('record 自身被外部破坏时也不外抛（队列 push 失败路径）', () => {
    vi.stubGlobal('fetch', undefined)
    try {
      // Date.now 抛错 → entry 组装失败 → reportRenderError 必须吞掉
      vi.spyOn(Date, 'now').mockImplementation(() => {
        throw new Error('clock broken')
      })
      try {
        expect(() => reportRenderError(new Error('no throw 2'))).not.toThrow()
      } finally {
        vi.restoreAllMocks()
      }
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
