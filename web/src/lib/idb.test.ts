import { expect, it } from 'vitest'
import { runTx } from './idb'

function fakeTransaction() {
  const request = { result: 42, error: null, onsuccess: null as (() => void) | null, onerror: null as (() => void) | null }
  const transaction = { error: null as Error | null, oncomplete: null as (() => void) | null, onabort: null as (() => void) | null, objectStore: () => ({}) }
  const db = { transaction: () => transaction } as unknown as IDBDatabase
  return { request, transaction, promise: runTx(db, 'synthetic', 'readwrite', () => request as unknown as IDBRequest<number>) }
}

it('request 成功不提前确认，commit 完成才 resolve', async () => {
  const f = fakeTransaction()
  let resolved = false
  void f.promise.then(() => { resolved = true })
  f.request.onsuccess?.()
  await Promise.resolve()
  expect(resolved).toBe(false)
  f.transaction.oncomplete?.()
  expect(await f.promise).toBe(42)
})

it('request 成功后提交 abort 仍拒绝', async () => {
  const f = fakeTransaction()
  f.request.onsuccess?.()
  f.transaction.error = new Error('synthetic commit abort')
  f.transaction.onabort?.()
  await expect(f.promise).rejects.toThrow('synthetic commit abort')
})
