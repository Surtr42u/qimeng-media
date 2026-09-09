/**
 * 打点本地账核心锁定（任务L L5「本地优先」；lib/event-ledger 纯函数，node 环境）：
 * 记账即幂等键 / 上报成功删除 / 失败保留同 id 重试（补发路径）/ 全失败退避闸 /
 * 空账本幂等。存储与出网用内存假件（禁 MockK 同族约定：fake 不 mock）。
 */
import { describe, expect, it } from 'vitest'
import { createEventLedger, FLUSH_FAILURE_BACKOFF_MS, type EventLedgerStorage, type LedgerViewEvent } from './event-ledger'

/** 内存存储（IndexedDB 实现的语义复刻：keyPath=clientEventId 唯一） */
function memoryStorage(): EventLedgerStorage & { rows: Map<string, LedgerViewEvent> } {
  const rows = new Map<string, LedgerViewEvent>()
  return {
    rows,
    async put(event) {
      rows.set(event.clientEventId, event)
    },
    async list() {
      return [...rows.values()]
    },
    async delete(id) {
      rows.delete(id)
    },
  }
}

/** 可编程出网假件：记录每次收到的完整行（同 id 断言用），按序弹出一发即中的裁决 */
function fakeSender(verdicts: Array<'ok' | 'fail'> = []) {
  const received: LedgerViewEvent[] = []
  let call = 0
  const sender = (event: LedgerViewEvent): Promise<void> => {
    received.push(event)
    const verdict = verdicts[Math.min(call, verdicts.length - 1)] ?? 'ok'
    call++
    return verdict === 'ok' ? Promise.resolve() : Promise.reject(new Error('network down'))
  }
  return { sender, received }
}

/** 可拨时钟（退避闸判定注入） */
function fakeClock() {
  const state = { current: 1_000_000 }
  return {
    now: () => state.current,
    advance: (ms: number) => {
      state.current += ms
    },
  }
}

function openEvent() {
  return {
    assetId: '00000000-0000-0000-0000-000000000001',
    kind: 'open' as const,
    startedAt: '2026-09-09T12:00:00.000Z',
    sessionId: 'sess-web-1',
  }
}

describe('event-ledger 本地优先账本', () => {
  it('记账 - 入账即生成 UUID 幂等键并落存储，自动补发成功后删除', async () => {
    const storage = memoryStorage()
    const { sender, received } = fakeSender(['ok'])
    const ledger = createEventLedger({ storage, sender })

    await ledger.record(openEvent())

    const [first] = received
    expect(first).toBeDefined()
    expect(first.clientEventId).toMatch(/^[0-9a-f-]{36}$/)
    expect(first.assetId).toBe(openEvent().assetId)
    expect(await ledger.pendingCount()).toBe(0) // 成功即删
    expect(storage.rows.size).toBe(0)
  })

  it('上报失败 - 行保留在账本（失败也有本地的），重试携带同一 clientEventId', async () => {
    const storage = memoryStorage()
    const { sender, received } = fakeSender(['fail', 'ok'])
    const clock = fakeClock()
    const ledger = createEventLedger({ storage, sender, now: clock.now })

    await ledger.record(openEvent())
    expect(await ledger.pendingCount()).toBe(1)

    // 全失败起闸后拨过退避时长（重开页面/online 触发的补发路径）：
    // 成功清空，且重发 id 与首发一致（服务端幂等去重的前提）
    clock.advance(FLUSH_FAILURE_BACKOFF_MS + 1)
    expect(await ledger.scheduleFlush()).toEqual({ sent: 1, kept: 0 })
    expect(await ledger.pendingCount()).toBe(0)
    expect(received).toHaveLength(2)
    expect(received[0].clientEventId).toBe(received[1].clientEventId)
  })

  it('退避闸 - 全失败后闸内触发跳过（null），拨过退避时长后恢复补发', async () => {
    const storage = memoryStorage()
    const { sender, received } = fakeSender(['fail', 'ok'])
    const clock = fakeClock()
    const ledger = createEventLedger({ storage, sender, now: clock.now })

    await ledger.record(openEvent()) // 自动补发：全失败 → 起闸
    expect(await ledger.pendingCount()).toBe(1)

    // 闸内：触发直接跳过，不发网
    expect(await ledger.scheduleFlush()).toBeNull()
    expect(received).toHaveLength(1)

    // 拨过 FLUSH_FAILURE_BACKOFF_MS：恢复补发，成功删行
    clock.advance(FLUSH_FAILURE_BACKOFF_MS + 1)
    expect(await ledger.scheduleFlush()).toEqual({ sent: 1, kept: 0 })
    expect(await ledger.pendingCount()).toBe(0)
  })

  it('空账本 - flush 幂等无害且不触网', async () => {
    const storage = memoryStorage()
    const { sender, received } = fakeSender()
    const ledger = createEventLedger({ storage, sender })

    expect(await ledger.flush()).toEqual({ sent: 0, kept: 0 })
    expect(received).toHaveLength(0)
  })

  it('dwell 事件 - seconds 随行透传（累加口径的载荷完整性）', async () => {
    const storage = memoryStorage()
    const { sender, received } = fakeSender(['ok'])
    const ledger = createEventLedger({ storage, sender })

    await ledger.record({ ...openEvent(), kind: 'dwell', seconds: 45 })
    expect(received[0]?.seconds).toBe(45)
  })
})
