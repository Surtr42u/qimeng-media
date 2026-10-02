/**
 * 上传队列持久化核心锁定（node 环境，零 IndexedDB 依赖）：同名续传决策
 *（decideResume）、恢复条目映射（toRestoredEntry）、存储端口契约的写读往返
 *（经 lib/upload-queue-persist-instance 单例——node 无 indexedDB 自然落内存
 * 实现，契约与浏览器 IndexedDB 实现同形）。
 */
import { describe, expect, it } from 'vitest'

import { getUploadQueueStorage } from './upload-queue-persist-instance'
import { decideResume, toRestoredEntry, type PersistedUploadItem } from './upload-queue-persist'

/** 记录样例（字段最小完备；sizeBytes 16MB = 分片阈值下限，锁分片条目形态） */
function sampleRecord(overrides: Partial<PersistedUploadItem> = {}): PersistedUploadItem {
  return {
    id: 'upload-7',
    name: 'video.mp4',
    sizeBytes: 16 * 1024 * 1024,
    targetLibraryId: 'lib-1',
    targetDir: 'sub/dir',
    sessionId: 'sess-1',
    chunkOffset: 8 * 1024 * 1024,
    createdAt: 1700000000000,
    updatedAt: 1700000001000,
    ...overrides,
  }
}

describe('decideResume 同名续传决策', () => {
  it('同名同大小且有会话 id → resume-session（带会话 id）', () => {
    const decision = decideResume(sampleRecord(), { name: 'video.mp4', size: 16 * 1024 * 1024 })
    expect(decision).toEqual({ kind: 'resume-session', sessionId: 'sess-1' })
  })

  it('同名同大小无会话（直传/会话未建立）→ restart', () => {
    const decision = decideResume(sampleRecord({ sessionId: null }), { name: 'video.mp4', size: 16 * 1024 * 1024 })
    expect(decision).toEqual({ kind: 'restart' })
  })

  it('文件名不符 → mismatch（拒绝把别的文件传进原条目目标位）', () => {
    const decision = decideResume(sampleRecord(), { name: 'other.mp4', size: 16 * 1024 * 1024 })
    expect(decision).toEqual({ kind: 'mismatch' })
  })

  it('大小不符 → mismatch（同名不同文件不续传）', () => {
    const decision = decideResume(sampleRecord(), { name: 'video.mp4', size: 1024 })
    expect(decision).toEqual({ kind: 'mismatch' })
  })

  it('编辑过基名的条目按 originalName 判定（重选的是磁盘原名文件）', () => {
    const record = sampleRecord({ name: '编辑名.mp4', originalName: 'video.mp4' })
    expect(decideResume(record, { name: 'video.mp4', size: 16 * 1024 * 1024 })).toEqual({
      kind: 'resume-session',
      sessionId: 'sess-1',
    })
    expect(decideResume(record, { name: '编辑名.mp4', size: 16 * 1024 * 1024 })).toEqual({ kind: 'mismatch' })
  })
})

describe('toRestoredEntry 刷新恢复映射', () => {
  it('记录字段逐一还原，无文件句柄字段，断点偏移推导百分比', () => {
    const restored = toRestoredEntry(sampleRecord())
    expect(restored).toEqual({
      id: 'upload-7',
      name: 'video.mp4',
      originalName: undefined,
      sizeBytes: 16 * 1024 * 1024,
      percent: 50,
      targetLibraryId: 'lib-1',
      targetDir: 'sub/dir',
      attach: undefined,
      sessionId: 'sess-1',
      chunkOffset: 8 * 1024 * 1024,
      createdAt: 1700000000000,
    })
    // 平台客观限制的形态保证：恢复条目不携带任何可用的文件句柄
    expect('file' in restored).toBe(false)
  })

  it('偏移越界夹取：超大小按 100%、零大小不产生 NaN', () => {
    expect(toRestoredEntry(sampleRecord({ chunkOffset: 999 * 1024 * 1024 })).percent).toBe(100)
    expect(toRestoredEntry(sampleRecord({ sizeBytes: 0, chunkOffset: 0 })).percent).toBe(0)
    expect(toRestoredEntry(sampleRecord({ sessionId: null, chunkOffset: 0 })).percent).toBe(0)
  })
})

describe('存储端口契约：写读往返（内存实现；IndexedDB 实现同契约）', () => {
  it('put → getAll 原样读回；同 id put 原位覆盖；delete 后消失', async () => {
    const storage = await getUploadQueueStorage()
    const first = sampleRecord()
    const second = sampleRecord({ id: 'upload-8', name: 'b.mp4', sessionId: null, chunkOffset: 0 })

    await storage.put(first)
    await storage.put(second)
    const listed = await storage.getAll()
    expect(listed).toContainEqual(first)
    expect(listed).toContainEqual(second)

    // keyPath=id：同 id 重复写不产生双行（原位覆盖）
    const overwritten = sampleRecord({ chunkOffset: 12 * 1024 * 1024 })
    await storage.put(overwritten)
    const afterOverwrite = await storage.getAll()
    expect(afterOverwrite.filter((it) => it.id === 'upload-7')).toEqual([overwritten])

    await storage.delete('upload-7')
    expect((await storage.getAll()).some((it) => it.id === 'upload-7')).toBe(false)
    await storage.delete('upload-8') // 清场，不污染同文件其他用例
  })
})
