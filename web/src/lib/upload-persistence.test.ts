import { describe, expect, it, vi } from 'vitest'
import { UploadPersistence } from './upload-persistence'
import type { PersistedUploadItem, UploadQueueStorage } from './upload-queue-persist'

const item = (id: string): PersistedUploadItem => ({ id, uploadId: 'synthetic-stable-upload', name: 'synthetic.jpg', sizeBytes: 1, targetLibraryId: 'synthetic', targetDir: '', sessionId: null, chunkOffset: 0, createdAt: 1, updatedAt: 1 })

function fixture() {
  const rows = new Map<string, PersistedUploadItem>()
  const fail = { put: false, delete: false, read: false }
  const storage: UploadQueueStorage = {
    durable: true,
    async getAll() { if (fail.read) throw new Error('synthetic read'); return [...rows.values()] },
    async put(row) { if (fail.put) throw new Error('synthetic put'); rows.set(row.id, row) },
    async delete(id) { if (fail.delete) throw new Error('synthetic delete'); rows.delete(id) },
  }
  return { rows, fail, storage, journal: new UploadPersistence(async () => storage, () => {}) }
}

describe('上传持久化健康与 IO 意图', () => {
  it('写失败与删除失败分别记账，重试恢复原 uploadId，不触发业务重传', async () => {
    const f = fixture()
    f.fail.put = true
    f.journal.put(item('active'))
    await vi.waitFor(() => expect(f.journal.getHealth().failedWrites).toBe(1))
    f.rows.set('finished', item('finished'))
    f.fail.delete = true
    f.journal.delete('finished')
    await vi.waitFor(() => expect(f.journal.getHealth().failedDeletes).toBe(1))
    // 终态残留即使还在库里，本页面的恢复也不得重新挂回队列。
    expect(await f.journal.read()).toEqual([])
    f.fail.put = false
    f.fail.delete = false
    await f.journal.retry()
    expect(f.journal.getHealth()).toMatchObject({ failedWrites: 0, failedDeletes: 0, retrying: false })
    expect(f.rows.get('active')?.uploadId).toBe('synthetic-stable-upload')
    expect(f.rows.has('finished')).toBe(false)
  })

  it('较慢旧 put 与取消删除串行，迟到进度不能复活终态', async () => {
    const f = fixture()
    let release!: () => void
    let entered!: () => void
    const started = new Promise<void>((resolve) => { entered = resolve })
    const gate = new Promise<void>((resolve) => { release = resolve })
    f.storage.put = async (row) => { entered(); await gate; f.rows.set(row.id, row) }
    f.journal.put(item('canceled'))
    await started
    f.journal.delete('canceled')
    f.journal.put({ ...item('canceled'), chunkOffset: 1 })
    release()
    await f.journal.retry()
    expect(f.rows.has('canceled')).toBe(false)
    expect(await f.journal.read()).toEqual([])
  })

  it('删除故障尽力写终态，跨刷新不恢复为待上传', async () => {
    const f = fixture()
    f.journal.put(item('committed'))
    await vi.waitFor(() => expect(f.rows.has('committed')).toBe(true))
    f.fail.delete = true
    f.journal.delete('committed')
    await vi.waitFor(() => expect(f.journal.getHealth().failedDeletes).toBe(1))
    expect(f.rows.get('committed')?.terminal).toBe(true)
    const reloaded = new UploadPersistence(async () => f.storage, () => {})
    expect(await reloaded.read()).toEqual([])
    f.fail.delete = false
    await reloaded.retry()
    expect(f.rows.has('committed')).toBe(false)
  })

  it('初始化降级与恢复读取失败可见，成功无关写不能清掉其他失败', async () => {
    const f = fixture()
    f.storage.durable = false
    await f.journal.read()
    expect(f.journal.getHealth().unavailable).toBe(true)
    f.journal.put(item('pending'))
    await vi.waitFor(() => expect(f.journal.getHealth().failedWrites).toBe(1))
    f.storage.durable = true
    f.fail.read = true
    await f.journal.read()
    f.journal.put(item('other'))
    await vi.waitFor(() => expect(f.rows.has('other')).toBe(true))
    expect(f.journal.getHealth().failedWrites).toBe(1)
    expect(f.journal.getHealth().restoreFailed).toBe(true)
    f.fail.read = false
    await f.journal.retry()
    expect(f.journal.getHealth()).toMatchObject({ unavailable: false, restoreFailed: false, failedWrites: 0 })
  })
})
