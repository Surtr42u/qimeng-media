/**
 * IndexedDB 共享薄封装（promise 化）：本仓库第 2 个 IndexedDB 消费方
 *（打点账本 lib/ledger-instance → 上传队列持久化 lib/upload-queue-persist-instance）
 * 出现即抽共享件（AI_README_FIRST「代码卫生约束」6：第 2 次出现即抽共享函数）。
 * 只做 promise 化与建库升级两件事，无任何业务语义；存储实现（object store 结构、
 * 降级口径）仍归各消费方自己。
 */

/**
 * 打开数据库（版本号入库需递增，结构迁移只在 onupgradeneeded 做——只加不改不删，
 * 与服务端 migration 同纪律）。open 失败 reject（调用方自行降级）。
 */
export function openDatabase(
  name: string,
  version: number,
  upgrade: (db: IDBDatabase) => void,
): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(name, version)
    request.onupgradeneeded = () => upgrade(request.result)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('indexedDB open failed'))
  })
}

/**
 * 单事务封装：promise 化 request（结果经 request 取回，事务随请求落定）；
 * 事务 abort 视为失败（部分写入不生效）。
 */
export function runTx<T>(
  db: IDBDatabase,
  storeName: string,
  mode: IDBTransactionMode,
  run: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(storeName, mode)
    const request = run(transaction.objectStore(storeName))
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('indexedDB request failed'))
    transaction.onabort = () => reject(transaction.error ?? new Error('indexedDB transaction aborted'))
  })
}
