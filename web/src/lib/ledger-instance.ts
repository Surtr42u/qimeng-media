/**
 * 打点本地账的浏览器装配（任务L L5）：IndexedDB 存储实现 + 生成 SDK 出网实现 +
 * 进程级单例。核心语义全在纯账本 lib/event-ledger（单测锁定），本文件只做平台接线。
 *
 * 为什么 IndexedDB：本地账是「web 有 d 安卓有 e 合并成 a b c d e」的本端暂存，
 * 必须跨标签页/跨会话存活——localStorage 同步阻塞且容量小，IndexedDB 异步大容量
 * 且 keyPath 天然做幂等键唯一约束。IndexedDB open 失败（隐私模式禁持久化等）降级
 * 内存账：打点尽力而为，降级=标签页内存账，不阻塞浏览主链路。
 */

import { postApiV1EventsView } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { createEventLedger, type EventLedger, type EventLedgerStorage, type LedgerViewEvent } from '@/lib/event-ledger'

/** 数据库名/存储名（升级走 version 递增 + onupgradeneeded，只加不改不删） */
const DB_NAME = 'qimeng_event_ledger'
const DB_VERSION = 1
const STORE_NAME = 'view_events'

/**
 * IndexedDB 存储实现。keyPath=幂等键：put 天然按 clientEventId 唯一
 * （同 id 重复记账不产生双行，与服务端唯一索引同构）。
 */
function openIndexedDbStorage(): Promise<EventLedgerStorage> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'clientEventId' })
      }
    }
    request.onsuccess = () => {
      const db = request.result
      resolve({
        async put(event: LedgerViewEvent) {
          await tx(db, 'readwrite', (store) => store.put(event))
        },
        async list() {
          return tx(db, 'readonly', (store) => store.getAll())
        },
        async delete(clientEventId: string) {
          await tx(db, 'readwrite', (store) => store.delete(clientEventId))
        },
      })
    }
    request.onerror = () => reject(request.error ?? new Error('indexedDB open failed'))
  })
}

/** 单事务封装：promise 化 request（结果经 request 取回，事务随请求落定） */
function tx<T>(db: IDBDatabase, mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE_NAME, mode)
    const request = run(transaction.objectStore(STORE_NAME))
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('indexedDB request failed'))
    transaction.onabort = () => reject(transaction.error ?? new Error('indexedDB transaction aborted'))
  })
}

/** 内存存储（IndexedDB 不可用时的降级；标签页关闭即失，仅保会话内重试） */
export function createMemoryStorage(): EventLedgerStorage {
  const rows = new Map<string, LedgerViewEvent>()
  return {
    async put(event) {
      rows.set(event.clientEventId, event)
    },
    async list() {
      return [...rows.values()]
    },
    async delete(clientEventId) {
      rows.delete(clientEventId)
    },
  }
}

/** 生成 SDK 出网实现：202 确认 → resolve（删行）；非 2xx/网络失败 → 抛错（保留重试）。
 * LedgerViewEvent 与协议 ViewEventReport 同形（clientEventId 必填），整行透传。 */
async function sdkLedgerSender(event: LedgerViewEvent): Promise<void> {
  await unwrapSdkResult(postApiV1EventsView({ body: event }))
}

let ledgerPromise: Promise<EventLedger> | null = null

/**
 * 进程级单例（use-assets 打点与 use-event-ledger 补发触发共享同一账本）。
 * 首次调用装配存储后建账本；装配失败降级内存账，同一 promise 复用（无竞态切换）。
 */
export function getEventLedger(): Promise<EventLedger> {
  if (!ledgerPromise) {
    const storage =
      typeof indexedDB !== 'undefined'
        ? openIndexedDbStorage().catch(() => createMemoryStorage())
        : Promise.resolve(createMemoryStorage())
    ledgerPromise = storage.then((resolved) => createEventLedger({ storage: resolved, sender: sdkLedgerSender }))
  }
  return ledgerPromise
}
