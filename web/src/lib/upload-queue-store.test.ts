/**
 * 上传队列模块级单例 store 锁定（node 环境）：入队校验拦截（未选库整批拦 /
 * 大小上限前置拦）、abort 语义（cancelAll = 唯一 abort 触发点：在传 abort +
 * 排队标 canceled）、订阅-通知（useSyncExternalStore 的 subscribe/getItems
 * 契约）。XHR/出网用可编程假件（禁 MockK 同族约定：fake 不 mock）。
 *
 * store 是模块级单例：用例顺序设计为「上一用例不留未终态条目/在跑泵」，
 * 各用例独立；断言只看本用例入队条目，XHR 构造数取增量。重置型测试钩子
 * 不进生产面。
 */
import { describe, expect, it, vi } from 'vitest'

// api-client.ts 在模块顶层读 localStorage（token 缓存，浏览器专用全局），
// node 测试环境无此全局——先打桩再动态 import（sse.test.ts 同款前置）。
vi.stubGlobal('localStorage', {
  getItem: (): string | null => null,
  setItem: () => {},
  removeItem: () => {},
})

/** 可编程 XHR 假件：记录构造/send，成功与中止由用例手动裁决 */
class FakeXHR {
  /** 累计构造数（用例断言取增量，隔离单例跨用例残留） */
  static created = 0
  /** 全部实例（用例按序取用） */
  static instances: FakeXHR[] = []

  status = 0
  responseText = ''
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  upload = { onprogress: null as ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null }
  sent: File | null = null

  constructor() {
    FakeXHR.created++
    FakeXHR.instances.push(this)
  }

  open(): void {}
  setRequestHeader(): void {}
  send(file: File): void {
    this.sent = file
  }
  abort(): void {
    // 真实 XHR 的 onabort 仅在已开始发送后触发；假件按同语义裁决
    this.onabort?.()
  }
  /** 用例裁决：模拟 201 成功响应（泵收尾、不留跨用例在跑泵） */
  succeed(body: string): void {
    this.status = 201
    this.responseText = body
    this.onload?.()
  }
}

vi.stubGlobal('XMLHttpRequest', FakeXHR)

const { uploadQueueStore, BYTES_PER_MB } = await import('./upload-queue-store')

/** File 替身：store 只消费 name/size（鸭子类型，不构造真字节） */
function fakeFile(name: string, size = 1): File {
  return { name, size } as unknown as File
}

/** 微任务/定时器排空：串行泵的 await 链全部落地后再断言 */
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0))

describe('uploadQueueStore.enqueue 校验拦截', () => {
  it('未选库整批拦截：全部落 failed、零 XHR、逐条回调 onItemSettled', () => {
    const settled: string[] = []
    uploadQueueStore.setOptions({ maxBytesMb: null, onItemSettled: (it) => settled.push(it.id) })
    const xhrCountBefore = FakeXHR.created

    uploadQueueStore.enqueue(
      [{ file: fakeFile('a.mp4', 10) }, { file: fakeFile('b.mp4', 20) }],
      { libraryId: '', dir: '' },
    )

    const items = uploadQueueStore.getItems().slice(-2)
    expect(items.map((it) => it.status)).toEqual(['failed', 'failed'])
    expect(items.map((it) => it.errorText)).toEqual(['请先选择目标库', '请先选择目标库'])
    expect(FakeXHR.created).toBe(xhrCountBefore) // 不发任何请求
    expect(settled.length).toBe(2)
  })

  it('大小上限前置拦截：超限条目不入网（零 XHR），文案带上限值', () => {
    uploadQueueStore.setOptions({ maxBytesMb: 1 })
    const xhrCountBefore = FakeXHR.created

    uploadQueueStore.enqueue(
      [{ file: fakeFile('big.mp4', 1 * BYTES_PER_MB + 1) }],
      { libraryId: 'lib-1', dir: '' },
    )

    const item = uploadQueueStore.getItems().at(-1)
    expect(item?.status).toBe('failed')
    expect(item?.errorText).toBe('文件超过大小上限 1 MB，已拦截')
    expect(FakeXHR.created).toBe(xhrCountBefore) // 整批超限：泵无可拾取，零出网
  })
})

describe('uploadQueueStore abort 与订阅通知', () => {
  it('cancelAll：在传 abort 落 canceled、排队标 canceled，全程订阅收到通知', async () => {
    const onItemSettled = vi.fn()
    uploadQueueStore.setOptions({ maxBytesMb: null, onItemSettled })
    let notified = 0
    const unsubscribe = uploadQueueStore.subscribe(() => {
      notified++
    })

    uploadQueueStore.enqueue(
      [{ file: fakeFile('one.mp4', 1) }, { file: fakeFile('two.mp4', 2) }],
      { libraryId: 'lib-1', dir: '' },
    )
    await tick()

    // 订阅通知已发生（入队 + 泵置 uploading 至少两次）；首条在传、次条排队
    expect(notified).toBeGreaterThan(0)
    const [first, second] = uploadQueueStore.getItems().slice(-2)
    expect(first?.status).toBe('uploading')
    expect(second?.status).toBe('queued')
    expect(FakeXHR.instances.at(-1)?.sent).not.toBeNull()

    const notifiedBeforeCancel = notified
    uploadQueueStore.cancelAll()
    await tick()

    const [doneFirst, doneSecond] = uploadQueueStore.getItems().slice(-2)
    expect(doneFirst?.status).toBe('canceled')
    expect(doneSecond?.status).toBe('canceled')
    expect(notified).toBeGreaterThan(notifiedBeforeCancel) // 取消同样通知订阅方
    // canceled 是用户主动行为：不触发 onItemSettled（toast 口径，不弹打扰）
    expect(onItemSettled).not.toHaveBeenCalled()
    unsubscribe()
  })

  it('传输成功：2xx 落 done 并触发 onItemSettled（挂靠缺省直通终态）', async () => {
    const settled: string[] = []
    uploadQueueStore.setOptions({ maxBytesMb: null, onItemSettled: (it) => settled.push(it.id) })

    uploadQueueStore.enqueue([{ file: fakeFile('ok.mp4', 3) }], { libraryId: 'lib-1', dir: '' })
    await tick()
    FakeXHR.instances.at(-1)?.succeed('{"id":"asset-1"}')
    await tick()

    const item = uploadQueueStore.getItems().at(-1)
    expect(item?.status).toBe('done')
    expect(item?.percent).toBe(100)
    expect(settled.length).toBe(1)
  })
})
