/**
 * 上传队列模块级单例 store 锁定（node 环境）：入队校验拦截（未选库整批拦 /
 * 大小上限前置拦）、abort 语义（cancelAll = 唯一 abort 触发点：在传 abort +
 * 排队标 canceled）、订阅-通知（useSyncExternalStore 的 subscribe/getItems
 * 契约）、刷新持久化（恢复 needs-file 态 / 重选同名续传 / 终态清理）。
 * XHR/出网用可编程假件（禁 MockK 同族约定：fake 不 mock）。
 *
 * store 是模块级单例：用例顺序设计为「上一用例不留未终态条目/在跑泵」，
 * 各用例独立；断言只看本用例入队条目，XHR 构造数取增量。重置型测试钩子
 * 不进生产面。持久化存储经 lib/upload-queue-persist-instance 单例——node
 * 无 indexedDB 自然落内存实现，与本用例共享同一实例（种子/断言直达）。
 */
import { describe, expect, it, vi } from 'vitest'

import { getUploadQueueStorage } from './upload-queue-persist-instance'
import type { PersistedUploadItem } from './upload-queue-persist'

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
  /** open 实参（分片通道用例区分 create/probe/PATCH/complete 用） */
  method = ''
  url = ''

  constructor() {
    FakeXHR.created++
    FakeXHR.instances.push(this)
  }

  open(method: string, url: string): void {
    this.method = method
    this.url = url
  }
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
  /** 用例裁决：模拟任意状态码响应（分片通道要喂 2xx/探测体） */
  reply(status: number, body: string): void {
    this.status = status
    this.responseText = body
    this.onload?.()
  }
}

vi.stubGlobal('XMLHttpRequest', FakeXHR)

const { uploadQueueStore, BYTES_PER_MB } = await import('./upload-queue-store')

/** File 替身：store 只消费 name/size（鸭子类型，不构造真字节）；分片通道会
 *  调 slice 取片（返回假片即可——FakeXHR.send 只记录不消费） */
function fakeFile(name: string, size = 1): File {
  return {
    name,
    size,
    slice: () => ({} as Blob),
  } as unknown as File
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

// ---- 刷新持久化（HANDOVER §5 待办#2 的行为锁定）----

/** 16MB（分片通道阈值下限，upload-chunked CHUNKED_THRESHOLD_BYTES 同值——
 *  测试文件豁免魔法值规则，此处字面量写死避免 import 连带拉起 api-client） */
const MB16 = 16 * 1024 * 1024
/** 8MB（单片大小，upload-chunked CHUNK_BYTES 同值） */
const MB8 = 8 * 1024 * 1024

/** 种子持久化记录（默认已传半片的分片条目） */
function seedRecord(overrides: Partial<PersistedUploadItem>): PersistedUploadItem {
  return {
    id: 'upload-900',
    name: 'big.mp4',
    sizeBytes: MB16,
    targetLibraryId: 'lib-1',
    targetDir: '',
    sessionId: 'sess-900',
    chunkOffset: MB8,
    createdAt: 1700000000000,
    updatedAt: 1700000001000,
    ...overrides,
  }
}

describe('uploadQueueStore 刷新恢复（IndexedDB 持久化）', () => {
  it('种子记录还原为 needs-file 态：无文件句柄、断点推导进度、重复恢复不重复', async () => {
    const storage = await getUploadQueueStorage()
    await storage.put(seedRecord({}))
    await storage.put(seedRecord({ id: 'upload-901', sessionId: null, chunkOffset: 0 }))

    await uploadQueueStore.restorePersisted()

    const restored = uploadQueueStore.getItems().filter((it) => it.id === 'upload-900' || it.id === 'upload-901')
    expect(restored.map((it) => it.status)).toEqual(['needs-file', 'needs-file'])
    expect(restored.map((it) => it.percent)).toEqual([50, 0])
    expect(restored.map((it) => it.name)).toEqual(['big.mp4', 'big.mp4'])
    expect(restored.map((it) => it.targetLibraryId)).toEqual(['lib-1', 'lib-1'])

    const countBefore = uploadQueueStore.getItems().length
    await uploadQueueStore.restorePersisted()
    expect(uploadQueueStore.getItems().length).toBe(countBefore) // 幂等：不重复追加

    // 清场：移除恢复条目（同时验证记录同步删除）
    uploadQueueStore.removeItem('upload-900')
    uploadQueueStore.removeItem('upload-901')
    await tick()
    expect((await storage.getAll()).some((it) => it.id === 'upload-900' || it.id === 'upload-901')).toBe(false)
  })

  it('恢复条目 id 序号让位：新入队 id 不与恢复 id 冲突', async () => {
    const storage = await getUploadQueueStorage()
    await storage.put(seedRecord({ id: 'upload-950' }))
    await uploadQueueStore.restorePersisted()

    uploadQueueStore.setOptions({ maxBytesMb: null })
    uploadQueueStore.enqueue([{ file: fakeFile('fresh.mp4', 10) }], { libraryId: 'lib-1', dir: '' })
    await tick()

    const fresh = uploadQueueStore.getItems().find((it) => it.name === 'fresh.mp4')
    expect(fresh).toBeDefined()
    expect(Number(fresh?.id.slice('upload-'.length))).toBeGreaterThan(950) // 序号已让位

    // 清场：新条目送达终态 + 移除恢复条目（不留跨用例残留）
    FakeXHR.instances.at(-1)?.succeed('{"id":"asset-950"}')
    await tick()
    uploadQueueStore.removeItem('upload-950')
    await tick()
    expect((await storage.getAll()).some((it) => it.id === 'upload-950')).toBe(false)
  })
})

describe('uploadQueueStore 同名续传（恢复条目重选文件）', () => {
  it('名称或大小不符：留在 needs-file 并给文案，零出网', async () => {
    const storage = await getUploadQueueStorage()
    await storage.put(seedRecord({ id: 'upload-910' }))
    await uploadQueueStore.restorePersisted()

    const xhrBefore = FakeXHR.created
    uploadQueueStore.resumeWithFile('upload-910', fakeFile('another.mp4', MB16))
    let entry = uploadQueueStore.getItems().find((it) => it.id === 'upload-910')
    expect(entry?.status).toBe('needs-file')
    expect(entry?.errorText).toBe('所选文件与原条目不一致（文件名或大小不符），未恢复上传')
    expect(FakeXHR.created).toBe(xhrBefore) // 拒绝恢复不出网

    uploadQueueStore.resumeWithFile('upload-910', fakeFile('big.mp4', 12345))
    entry = uploadQueueStore.getItems().find((it) => it.id === 'upload-910')
    expect(entry?.status).toBe('needs-file')
    expect(FakeXHR.created).toBe(xhrBefore)

    uploadQueueStore.removeItem('upload-910') // 清场
    await tick()
  })

  it('同名同大小分片条目：按持久化会话探测续传（GET→PATCH→complete），断点推进即持久化', async () => {
    const storage = await getUploadQueueStorage()
    await storage.put(seedRecord({ id: 'upload-911' }))
    await uploadQueueStore.restorePersisted()

    uploadQueueStore.setOptions({ maxBytesMb: null })
    uploadQueueStore.resumeWithFile('upload-911', fakeFile('big.mp4', MB16))
    await tick()
    expect(uploadQueueStore.getItems().find((it) => it.id === 'upload-911')?.status).toBe('uploading')

    // 首跳 = GET 探测（既有会话 sess-900，跳过 create——既有协议内续传）
    expect(FakeXHR.instances.at(-1)?.method).toBe('GET')
    FakeXHR.instances.at(-1)?.reply(200, JSON.stringify({ offset: MB8 }))
    await tick()
    let record = (await storage.getAll()).find((it) => it.id === 'upload-911')
    expect(record?.sessionId).toBe('sess-900') // 会话 id 经 onSession 链路入记录
    expect(record?.chunkOffset).toBe(MB8)

    // PATCH 一片到达 size：响应权威 offset 推进并持久化
    FakeXHR.instances.at(-1)?.reply(200, JSON.stringify({ offset: MB16 }))
    await tick()
    record = (await storage.getAll()).find((it) => it.id === 'upload-911')
    expect(record?.chunkOffset).toBe(MB16)
    expect(uploadQueueStore.getItems().find((it) => it.id === 'upload-911')?.percent).toBe(100)

    // complete 201 → done 终态 + 持久化记录删除（不留孤儿）
    FakeXHR.instances.at(-1)?.reply(201, '{"id":"asset-911"}')
    await tick()
    expect(uploadQueueStore.getItems().find((it) => it.id === 'upload-911')?.status).toBe('done')
    expect((await storage.getAll()).some((it) => it.id === 'upload-911')).toBe(false)
  })

  it('同名同大小直传条目（无会话）：从头重传，终态清理持久化', async () => {
    const storage = await getUploadQueueStorage()
    await storage.put(seedRecord({ id: 'upload-912', sessionId: null, chunkOffset: 0, sizeBytes: 2048 }))
    await uploadQueueStore.restorePersisted()

    uploadQueueStore.resumeWithFile('upload-912', fakeFile('big.mp4', 2048))
    await tick()
    const xhr = FakeXHR.instances.at(-1)
    expect(xhr?.method).toBe('POST') // 直传通道整文件重发
    expect(xhr?.sent?.size).toBe(2048)

    xhr?.succeed('{"id":"asset-912"}')
    await tick()
    expect(uploadQueueStore.getItems().find((it) => it.id === 'upload-912')?.status).toBe('done')
    expect((await storage.getAll()).some((it) => it.id === 'upload-912')).toBe(false)
  })
})

describe('uploadQueueStore 持久化清理（终态/取消/清空完成）', () => {
  it('入队即持久化；直传成功终态删除记录', async () => {
    const storage = await getUploadQueueStorage()
    uploadQueueStore.setOptions({ maxBytesMb: null })
    uploadQueueStore.enqueue([{ file: fakeFile('cleanup-done.mp4', 12) }], { libraryId: 'lib-1', dir: '' })
    await tick()

    const item = uploadQueueStore.getItems().find((it) => it.name === 'cleanup-done.mp4')
    expect(item).toBeDefined()
    let record = (await storage.getAll()).find((it) => it.id === item?.id)
    expect(record?.sizeBytes).toBe(12) // 入队即持久化

    FakeXHR.instances.at(-1)?.succeed('{"id":"asset-cleanup"}')
    await tick()
    record = (await storage.getAll()).find((it) => it.id === item?.id)
    expect(record).toBeUndefined() // 终态即清理
  })

  it('全部取消：排队与在传条目的持久化记录一并删除', async () => {
    const storage = await getUploadQueueStorage()
    uploadQueueStore.enqueue(
      [{ file: fakeFile('cancel-a.mp4', 1) }, { file: fakeFile('cancel-b.mp4', 2) }],
      { libraryId: 'lib-1', dir: '' },
    )
    await tick()
    const ids = uploadQueueStore
      .getItems()
      .filter((it) => it.name === 'cancel-a.mp4' || it.name === 'cancel-b.mp4')
      .map((it) => it.id)
    expect(ids.length).toBe(2)
    expect((await storage.getAll()).filter((it) => ids.includes(it.id)).length).toBe(2)

    uploadQueueStore.cancelAll()
    await tick()
    expect((await storage.getAll()).filter((it) => ids.includes(it.id))).toEqual([])
  })

  it('清除已完成兜底清理：终态条目的漏删记录被清空，needs-file 保留', async () => {
    const storage = await getUploadQueueStorage()
    uploadQueueStore.enqueue([{ file: fakeFile('clear-finished.mp4', 5) }], { libraryId: 'lib-1', dir: '' })
    await tick()
    const itemId = uploadQueueStore.getItems().find((it) => it.name === 'clear-finished.mp4')?.id
    FakeXHR.instances.at(-1)?.succeed('{"id":"asset-clear"}')
    await tick()
    expect(uploadQueueStore.getItems().find((it) => it.id === itemId)?.status).toBe('done')

    // 模拟 settle 期存储短暂不可用的漏删记录：清除已完成须兜底清掉
    await storage.put(seedRecord({ id: itemId ?? '', name: 'clear-finished.mp4', sessionId: null, chunkOffset: 0 }))
    // 恢复条目不是完成态：清除已完成不得替用户丢弃
    await storage.put(seedRecord({ id: 'upload-930', sessionId: null, chunkOffset: 0 }))
    await uploadQueueStore.restorePersisted()

    uploadQueueStore.clearFinished()
    await tick()

    expect((await storage.getAll()).some((it) => it.id === itemId)).toBe(false)
    expect(uploadQueueStore.getItems().find((it) => it.id === 'upload-930')?.status).toBe('needs-file')
    uploadQueueStore.removeItem('upload-930') // 清场
    await tick()
  })
})
