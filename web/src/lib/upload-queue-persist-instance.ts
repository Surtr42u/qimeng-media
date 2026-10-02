/**
 * 上传队列持久化的浏览器装配（IndexedDB 实现 + 降级 + 进程级单例；核心语义
 * 全在纯模块 lib/upload-queue-persist，单测锁定——装配与语义分离的模式同
 * 打点账本 ledger-instance / event-ledger）。
 *
 * 独立库而非复用打点账本的 qimeng_event_ledger：两者生命周期不同（打点行
 * 高频滚动、队列行随条目清理），混库会让升级迁移互相牵连。IndexedDB 不可用
 * （隐私模式等）降级内存存储：持久化尽力而为，传输主链路不受影响（与打点
 * 账本同款降级口径，降级只丢「刷新恢复」这一增强能力）。
 */

import { openDatabase, runTx } from '@/lib/idb'
import type { PersistedUploadItem, UploadQueueStorage } from '@/lib/upload-queue-persist'

/** 数据库名/存储名（升级走 version 递增 + onupgradeneeded，只加不改不删） */
const DB_NAME = 'qimeng_upload_queue'
const DB_VERSION = 1
const STORE_NAME = 'queue_items'

/** IndexedDB 存储实现。keyPath=id：put 天然按条目 id 原位覆盖（同 id 重复写
 *  不产生双行），与 store 侧「入队写/推进写/终态删」的生命周期一一对应 */
function openQueueStorage(): Promise<UploadQueueStorage> {
  return openDatabase(DB_NAME, DB_VERSION, (db) => {
    if (!db.objectStoreNames.contains(STORE_NAME)) {
      db.createObjectStore(STORE_NAME, { keyPath: 'id' })
    }
  }).then((db) => ({
    async getAll() {
      return runTx(db, STORE_NAME, 'readonly', (store) => store.getAll())
    },
    async put(item: PersistedUploadItem) {
      await runTx(db, STORE_NAME, 'readwrite', (store) => store.put(item))
    },
    async delete(id: string) {
      await runTx(db, STORE_NAME, 'readwrite', (store) => store.delete(id))
    },
  }))
}

/** 内存存储（IndexedDB 不可用时的降级；页面刷新即失，仅保进程内一致） */
function createMemoryQueueStorage(): UploadQueueStorage {
  const rows = new Map<string, PersistedUploadItem>()
  return {
    async put(item) {
      rows.set(item.id, item)
    },
    async getAll() {
      return [...rows.values()]
    },
    async delete(id) {
      rows.delete(id)
    },
  }
}

let storagePromise: Promise<UploadQueueStorage> | null = null

/**
 * 进程级单例（store 与测试共享同一实例：node 测试环境无 indexedDB，自然落
 * 内存实现）。装配失败降级内存，同一 promise 复用（无竞态切换）。
 */
export function getUploadQueueStorage(): Promise<UploadQueueStorage> {
  if (!storagePromise) {
    storagePromise =
      typeof indexedDB !== 'undefined'
        ? openQueueStorage().catch(() => createMemoryQueueStorage())
        : Promise.resolve(createMemoryQueueStorage())
  }
  return storagePromise
}
