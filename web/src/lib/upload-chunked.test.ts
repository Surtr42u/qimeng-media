/**
 * 分片会话通道接入锁定（node 环境，ADR-0028 Web 接入批）：阈值分流两分支、
 * 全链分片序列与分片粒度进度、dir 透传 create、中断后从权威 offset 续传、
 * PATCH 409 权威体重同步、会话 404 重建（含上限不死循环）、网络错误指数退避
 * 重试（上限 3 次，超限终态失败不重新入队）、4xx 立即终态透传文案、取消时
 * abort + best-effort DELETE 会话、complete 后与直传完全相同的挂靠后处理。
 * XHR/出网用可编程假件（不 mock；挂靠生成 SDK 函数打桩——store 单例经
 * enqueue 驱动，断言取本用例增量）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

// api-client.ts 在模块顶层读 localStorage（token 缓存，浏览器专用全局），
// node 测试环境无此全局——先打桩再动态 import（upload-queue-store.test.ts 同款前置）。
vi.stubGlobal('localStorage', {
  getItem: (): string | null => null,
  setItem: () => {},
  removeItem: () => {},
})

// 挂靠序列走生成 SDK（store 成功后处理与直传共用）：打桩记录调用，不真出网
vi.mock('@/api/generated', () => ({
  putApiV1AssetsByAssetIdAuthors: vi.fn(async () => ({ data: {} })),
  putApiV1AuthorsByAuthorIdSources: vi.fn(async () => ({ data: {} })),
}))

/** 可编程 XHR 假件：记录 method/url/body，响应由用例逐步裁决 */
class FakeXHR {
  /** 累计构造数（用例断言取增量，隔离单例跨用例残留） */
  static created = 0
  /** 全部实例（用例按序取用） */
  static instances: FakeXHR[] = []

  method = ''
  url = ''
  status = 0
  responseText = ''
  timeout = 0
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  ontimeout: (() => void) | null = null
  /** 直传路径会挂 upload.onprogress（分流小文件用例经过），假件留位即可 */
  upload = { onprogress: null as unknown }
  sent: unknown = null

  constructor() {
    FakeXHR.created++
    FakeXHR.instances.push(this)
  }

  open(method: string, url: string): void {
    this.method = method
    this.url = url
  }

  setRequestHeader(): void {}

  send(body: unknown): void {
    this.sent = body
  }

  abort(): void {
    this.onabort?.()
  }

  /** 用例裁决：模拟任意状态码响应（会话通道要读 409 权威体与 4xx 文案） */
  respond(status: number, body: string): void {
    this.status = status
    this.responseText = body
    this.onload?.()
  }

  failNetwork(): void {
    this.onerror?.()
  }
}

vi.stubGlobal('XMLHttpRequest', FakeXHR)

const { uploadQueueStore, BYTES_PER_MB } = await import('./upload-queue-store')
const { CHUNKED_THRESHOLD_BYTES, CHUNK_BYTES, useChunkedSession } = await import('./upload-chunked')
const generated = await import('@/api/generated')

/** File 替身：store/分片通道消费 name/size/slice（slice 返回带 size 的标记对象） */
function fakeFile(name: string, size: number): File {
  return {
    name,
    size,
    slice: (start: number, end: number) => ({ start, size: end - start }),
  } as unknown as File
}

/** 微任务/定时器排空：串行泵与分片循环的 await 链全部落地后再断言 */
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0))

/** 20MB 测试文件（两整片 + 一余片）与 UploadSession 响应体 */
const FILE = 20 * BYTES_PER_MB
const CHUNK = CHUNK_BYTES
const sess = (offset: number): string => JSON.stringify({ id: 'sess-1', offset, size: FILE })

/** 本用例实例视图起点（单例 store/假件跨用例存活，断言只看增量） */
let fromIdx = 0
/** 取本用例发起的假件实例 */
const mine = (): FakeXHR[] => FakeXHR.instances.slice(fromIdx)
/** 排空微任务后返回最新实例（分片循环串行：上一请求裁决后下一请求才发出） */
async function next(): Promise<FakeXHR> {
  await tick()
  const list = mine()
  expect(list.length).toBeGreaterThan(0)
  return list.at(-1)!
}

beforeEach(() => {
  fromIdx = FakeXHR.instances.length
  uploadQueueStore.setOptions({ maxBytesMb: null, onItemSettled: () => {} })
})

describe('分片通道阈值分流', () => {
  it('useChunkedSession 纯函数两分支：恰达 16MB 走会话流、低 1 字节走直传', () => {
    expect(useChunkedSession(CHUNKED_THRESHOLD_BYTES)).toBe(true)
    expect(useChunkedSession(CHUNKED_THRESHOLD_BYTES - 1)).toBe(false)
  })

  it('<16MB 入队走直传端点（现状不变）、≥16MB 走 /api/v1/uploads 会话流', async () => {
    uploadQueueStore.enqueue([{ file: fakeFile('small.mp4', CHUNKED_THRESHOLD_BYTES - 1) }], { libraryId: 'lib-1', dir: '' })
    const direct = await next()
    expect(direct.method).toBe('POST')
    expect(direct.url.startsWith('/api/v1/assets/upload?')).toBe(true)
    direct.respond(201, '{"id":"a1"}')
    await tick()
    expect(uploadQueueStore.getItems().at(-1)?.status).toBe('done')

    uploadQueueStore.enqueue([{ file: fakeFile('big.mp4', CHUNKED_THRESHOLD_BYTES) }], { libraryId: 'lib-1', dir: '' })
    const create = await next()
    expect(create.method).toBe('POST')
    expect(create.url).toBe('/api/v1/uploads')
    create.respond(201, sess(0))
    // 收尾：探测/分片/complete 全部放行，不留跨用例在跑泵
    ;(await next()).respond(200, sess(0))
    ;(await next()).respond(200, sess(CHUNK))
    ;(await next()).respond(200, sess(CHUNKED_THRESHOLD_BYTES))
    ;(await next()).respond(201, '{"id":"a2"}')
    await tick()
    expect(uploadQueueStore.getItems().at(-1)?.status).toBe('done')
  })
})

describe('分片全链与断点语义', () => {
  it('全链：create 带 dir → 探测 → 三片 PATCH（offset 序列+片长）→ complete → 与直传同款挂靠后处理', async () => {
    const settled: string[] = []
    uploadQueueStore.setOptions({ maxBytesMb: null, onItemSettled: (it) => settled.push(it.id) })

    uploadQueueStore.enqueue(
      [{ file: fakeFile('big.mp4', FILE), attach: { authorId: 'author-1', authorName: '作者', sources: ['网盘'] } }],
      { libraryId: 'lib-1', dir: 'media/movies' },
    )

    const create = await next()
    expect(create.url).toBe('/api/v1/uploads')
    // dir 透传 create（批次目录快照；与直传 dir 参数逐字同语义，空串=库根）
    expect(JSON.parse(create.sent as string)).toEqual({ libraryId: 'lib-1', fileName: 'big.mp4', dir: 'media/movies', size: FILE })
    create.respond(201, sess(0))

    const probe = await next()
    expect(probe.method).toBe('GET')
    expect(probe.url).toBe('/api/v1/uploads/sess-1')
    probe.respond(200, sess(0))

    const p1 = await next()
    expect(p1.method).toBe('PATCH')
    expect(p1.url).toBe(`/api/v1/uploads/sess-1?offset=0`)
    expect((p1.sent as { size: number }).size).toBe(CHUNK)
    p1.respond(200, sess(CHUNK))
    await tick() // onProgress 经微任务落到权威副本
    expect(uploadQueueStore.getItems().at(-1)?.percent).toBe(40) // 分片粒度一跳

    const p2 = await next()
    expect(p2.url).toBe(`/api/v1/uploads/sess-1?offset=${CHUNK}`)
    expect((p2.sent as { size: number }).size).toBe(CHUNK)
    p2.respond(200, sess(CHUNK * 2))
    await tick()
    expect(uploadQueueStore.getItems().at(-1)?.percent).toBe(80)

    const p3 = await next()
    expect((p3.sent as { size: number }).size).toBe(FILE - CHUNK * 2) // 末片取余
    p3.respond(200, sess(FILE))
    await tick()
    expect(uploadQueueStore.getItems().at(-1)?.percent).toBe(100)

    const complete = await next()
    expect(complete.url).toBe('/api/v1/uploads/sess-1/complete')
    complete.respond(201, '{"id":"asset-9","fileName":"final.mp4"}')

    for (let i = 0; i < 6; i++) await tick() // 挂靠序列（两次 SDK 调用）落地
    const item = uploadQueueStore.getItems().at(-1)!
    expect(item.status).toBe('done')
    expect(item.percent).toBe(100)
    expect(settled.length).toBe(1)
    // 与直传 201 后完全相同的后处理：先 authors 单项替换、后 sources append
    expect(vi.mocked(generated.putApiV1AssetsByAssetIdAuthors)).toHaveBeenCalledWith({
      path: { assetId: 'asset-9' },
      body: { authorIds: ['author-1'] },
    })
    expect(vi.mocked(generated.putApiV1AuthorsByAuthorIdSources)).toHaveBeenCalledWith({
      path: { authorId: 'author-1' },
      body: { sources: ['网盘'], mode: 'append' },
    })
  })

  it('PATCH 409：响应体权威 offset 立即重同步续传（不算失败）', async () => {
    uploadQueueStore.enqueue([{ file: fakeFile('big.mp4', FILE) }], { libraryId: 'lib-1', dir: '' })
    ;(await next()).respond(201, sess(0))
    ;(await next()).respond(200, sess(0))

    const stale = await next()
    stale.respond(409, sess(4 * BYTES_PER_MB)) // 服务端已有 4MB（权威漂移）

    const resync = await next()
    expect(resync.method).toBe('PATCH')
    expect(resync.url).toBe(`/api/v1/uploads/sess-1?offset=${4 * BYTES_PER_MB}`)
    expect((resync.sent as { size: number }).size).toBe(CHUNK)
    resync.respond(200, sess(4 * BYTES_PER_MB + CHUNK))
    ;(await next()).respond(200, sess(FILE))
    ;(await next()).respond(201, '{"id":"a3"}')
    await tick()
    expect(uploadQueueStore.getItems().at(-1)?.status).toBe('done')
  })

  it('网络错误退避重试：每次重试先 GET 探测从权威 offset 续传', async () => {
    vi.useFakeTimers()
    try {
      const step = async (advanceMs = 0): Promise<FakeXHR> => {
        await vi.advanceTimersByTimeAsync(advanceMs)
        await Promise.resolve()
        return mine().at(-1)!
      }

      uploadQueueStore.enqueue([{ file: fakeFile('big.mp4', FILE) }], { libraryId: 'lib-1', dir: '' })
      ;(await step()).respond(201, sess(0))
      ;(await step()).respond(200, sess(CHUNK)) // 服务端已收下第一片（首次探测返回 8MB）

      const p1 = await step()
      expect(p1.method).toBe('PATCH')
      expect(p1.url).toBe(`/api/v1/uploads/sess-1?offset=${CHUNK}`)
      p1.failNetwork() // 第二片发到一半断网

      const reprobe = await step(1000) // 退避 1s → 重试#1 先探测
      expect(reprobe.method).toBe('GET')
      expect(reprobe.url).toBe('/api/v1/uploads/sess-1')
      reprobe.respond(200, sess(CHUNK))
      const p2 = await step()
      expect(p2.url).toBe(`/api/v1/uploads/sess-1?offset=${CHUNK}`)
      p2.respond(200, sess(CHUNK * 2))
      ;(await step()).respond(200, sess(FILE))
      ;(await step()).respond(201, '{"id":"a4"}')
      await step()
      expect(uploadQueueStore.getItems().at(-1)?.status).toBe('done')
      expect(mine().filter((x) => x.method === 'PATCH')).toHaveLength(3)
    } finally {
      vi.useRealTimers()
    }
  })

  it('重试上限：4 次 PATCH 全败后终态失败带文案，不自动重新入队、不再出网', async () => {
    vi.useFakeTimers()
    try {
      const step = async (advanceMs = 0): Promise<FakeXHR> => {
        await vi.advanceTimersByTimeAsync(advanceMs)
        await Promise.resolve()
        return mine().at(-1)!
      }

      uploadQueueStore.enqueue([{ file: fakeFile('big.mp4', FILE) }], { libraryId: 'lib-1', dir: '' })
      ;(await step()).respond(201, sess(0)) // create
      ;(await step()).respond(200, sess(0)) // 首次探测
      for (let i = 0; i < 4; i++) {
        const patch = await step()
        expect(patch.method).toBe('PATCH')
        patch.failNetwork()
        if (i < 3) {
          const reprobe = await step(1000 * 2 ** i) // 1s/2s/4s 指数退避 → 探测
          expect(reprobe.method).toBe('GET')
          reprobe.respond(200, sess(0))
        }
      }
      await step()

      const item = uploadQueueStore.getItems().at(-1)!
      expect(item.status).toBe('failed')
      expect(item.errorText).toContain('自动重试 3 次仍失败')
      expect(mine().filter((x) => x.method === 'PATCH')).toHaveLength(4) // 首次 + 3 次重试
      const outCount = FakeXHR.created
      await step(10000)
      expect(FakeXHR.created).toBe(outCount) // 重试上限后停止：不自动重新入队
    } finally {
      vi.useRealTimers()
    }
  })

  it('会话 404 重建：探测 404 后重新 create 从 0 续传（成功路径）', async () => {
    uploadQueueStore.enqueue([{ file: fakeFile('big.mp4', FILE) }], { libraryId: 'lib-1', dir: '' })
    ;(await next()).respond(201, sess(0))
    const probe1 = await next()
    probe1.respond(404, '{"code":"UPLOAD_SESSION_NOT_FOUND","message":"会话不存在或已过期"}')

    const create2 = await next() // 重建：第二次 create
    expect(create2.url).toBe('/api/v1/uploads')
    create2.respond(201, sess(0))
    ;(await next()).respond(200, sess(FILE - CHUNK)) // 重建后从权威 offset（此处近尾）续传
    ;(await next()).respond(200, sess(FILE))
    ;(await next()).respond(201, '{"id":"a5"}')
    await tick()
    expect(mine().filter((x) => x.method === 'POST' && x.url === '/api/v1/uploads')).toHaveLength(2)
    expect(uploadQueueStore.getItems().at(-1)?.status).toBe('done')
  })

  it('会话 404 重建上限：连续 404 重建 2 次后终态失败（防死循环、不再出网）', async () => {
    uploadQueueStore.enqueue([{ file: fakeFile('big.mp4', FILE) }], { libraryId: 'lib-1', dir: '' })
    for (let i = 0; i < 3; i++) {
      ;(await next()).respond(201, sess(0)) // create ×3（首次 + 重建 2 次）
      ;(await next()).respond(404, '{"message":"会话不存在"}') // 每次探测都 404
    }
    await tick()

    const item = uploadQueueStore.getItems().at(-1)!
    expect(item.status).toBe('failed')
    expect(item.errorText).toContain('反复失效')
    expect(mine().filter((x) => x.method === 'POST' && x.url === '/api/v1/uploads')).toHaveLength(3)
    const outCount = FakeXHR.created
    await tick()
    expect(FakeXHR.created).toBe(outCount)
  })

  it('4xx 校验类：立即终态失败透传服务端 message，零重试', async () => {
    uploadQueueStore.enqueue([{ file: fakeFile('big.mp4', FILE) }], { libraryId: 'lib-1', dir: '' })
    ;(await next()).respond(201, sess(0))
    ;(await next()).respond(200, sess(0))
    ;(await next()).respond(400, '{"code":"UPLOAD_TOO_LARGE","message":"累计字节超出会话声明的大小"}')
    await tick()

    const item = uploadQueueStore.getItems().at(-1)!
    expect(item.status).toBe('failed')
    expect(item.errorText).toBe('累计字节超出会话声明的大小')
    expect(mine().filter((x) => x.method === 'PATCH')).toHaveLength(1)
  })

  it('全部取消：在途分片 abort 落 canceled + best-effort DELETE 会话', async () => {
    const onItemSettled = vi.fn()
    uploadQueueStore.setOptions({ maxBytesMb: null, onItemSettled })
    uploadQueueStore.enqueue([{ file: fakeFile('big.mp4', FILE) }], { libraryId: 'lib-1', dir: '' })
    ;(await next()).respond(201, sess(0))
    ;(await next()).respond(200, sess(0))
    await next() // PATCH 在途，不裁决

    uploadQueueStore.cancelAll()
    await tick()

    expect(uploadQueueStore.getItems().at(-1)?.status).toBe('canceled')
    expect(onItemSettled).not.toHaveBeenCalled() // canceled 是用户主动行为：不弹打扰
    const abandon = mine().find((x) => x.method === 'DELETE')
    expect(abandon?.url).toBe('/api/v1/uploads/sess-1')
  })
})
