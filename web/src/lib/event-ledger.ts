/**
 * 打点本地账核心（任务L L5「本地优先」；用户原话 #25「web 和手机都是单独保存本地
 * 一份再上报合并……失败也有本地的」）。
 *
 * 本文件是**纯账本**：存储与出网都是注入端口（浏览器 IndexedDB 实现与生成 SDK 出网
 * 在 lib/ledger-instance 装配，React 侧接线在 hooks/use-event-ledger），因此 lib 纯
 * 函数单测（node 环境）无需 IndexedDB 与网络。
 *
 * 口径（拍板 #7，与服务端 migration 0010 幂等键语义互为镜像）：
 * - 事件先落本地（含 clientEventId UUID，入账即生成、随行持久）；
 * - 上报成功（sender 不抛错）才从账本删除；
 * - 失败（网络/5xx）保留原行，补发携带**同一** clientEventId——服务端唯一索引幂等，
 *   重发不双计（dwell 秒数不重复累加、open/play 不重复计数）；
 * - 全失败的整轮 flush 触发退避闸（FLUSH_FAILURE_BACKOFF_MS），退避期内的补发触发
 *   直接跳过，防对故障端点狂打；任一条成功即解除闸。
 */

import { randomUUID } from './uuid'

/** 账本行 = 协议 ViewEventReport 形态（clientEventId 必填，openapi 2026-09-09 L5） */
export interface LedgerViewEvent {
  clientEventId: string
  assetId: string
  kind: 'open' | 'play' | 'dwell'
  startedAt: string
  sessionId: string
  seconds?: number
}

/** 存储端口：浏览器实现=IndexedDB（keyPath clientEventId），单测=内存假件 */
export interface EventLedgerStorage {
  put(event: LedgerViewEvent): Promise<void>
  list(): Promise<LedgerViewEvent[]>
  delete(clientEventId: string): Promise<void>
}

/** 出网端口：resolve=服务端确认（2xx），抛错=失败保留（重试携带同一 id） */
export type LedgerSender = (event: LedgerViewEvent) => Promise<void>

export interface EventLedgerOptions {
  storage: EventLedgerStorage
  sender: LedgerSender
  /** 时钟注入（退避闸判定；缺省 Date.now，单测定死） */
  now?: () => number
}

/** 一轮补发摘要：sent=确认并删除的条数；kept=失败保留的条数 */
export interface FlushResult {
  sent: number
  kept: number
}

/** 全失败的退避闸时长（毫秒）：周期补发触发在闸内直接跳过 */
export const FLUSH_FAILURE_BACKOFF_MS = 60_000

export function createEventLedger({ storage, sender, now = Date.now }: EventLedgerOptions) {
  /** 退避闸到期时刻（0=无闸）；任一条成功即重置 */
  let flushAllowedAt = 0
  /** 单飞闸：并发触发合并成同一轮（flush 本身逐条幂等，无需排队多轮） */
  let inFlight: Promise<FlushResult> | null = null

  async function flush(): Promise<FlushResult> {
    if (inFlight) return inFlight
    inFlight = (async () => {
      const rows = await storage.list()
      let sent = 0
      let kept = 0
      for (const row of rows) {
        try {
          await sender(row)
          await storage.delete(row.clientEventId)
          sent++
        } catch {
          // 失败保留原行（本地优先），同 id 重试
          kept++
        }
      }
      // 全失败 → 起退避闸；有成功 → 解除
      flushAllowedAt = sent === 0 && kept > 0 ? now() + FLUSH_FAILURE_BACKOFF_MS : 0
      return { sent, kept }
    })()
    try {
      return await inFlight
    } finally {
      inFlight = null
    }
  }

  /**
   * 补发触发入口（受退避闸与单飞闸约束；record 成功后与各触发通道共用）。
   * 返回本轮摘要；闸内跳过返回 null（调用方无须区分，仅测试与日志用）。
   */
  function scheduleFlush(): Promise<FlushResult | null> {
    if (now() < flushAllowedAt) return Promise.resolve(null)
    return flush()
  }

  /** 记账：入账即生成幂等键并持久，随后自动请求一轮补发（尽力而为，不阻塞调用方语义） */
  async function record(event: Omit<LedgerViewEvent, 'clientEventId'>): Promise<void> {
    await storage.put({ ...event, clientEventId: randomUUID() })
    await scheduleFlush()
  }

  /** 待上传存量（补发触发与诊断用） */
  async function pendingCount(): Promise<number> {
    return (await storage.list()).length
  }

  return { record, scheduleFlush, flush, pendingCount }
}

export type EventLedger = ReturnType<typeof createEventLedger>
