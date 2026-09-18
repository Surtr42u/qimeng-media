/**
 * 客户端异常上报器（POST /api/v1/client-logs，铁律 7 的 lib 层：组件不直接调 API）。
 *
 * 捕获面：window.onerror（未捕获同步异常）+ unhandledrejection（未处理的
 * Promise 拒绝），一律记 level=error，page=location.pathname。
 *
 * 发送策略：模块级队列，满 10 条立即 flush，否则 30s 定时 flush；
 * flush 失败（网络断/401/4xx）静默丢弃本批并清队列防积压——上报是旁路
 * 设施，绝不重试放大故障。防递归：flush 自身的错误只 console.warn 留痕，
 * 绝不回流到 record/install 的捕获管道（onerror 里再 enqueue 上报器自身
 * 的异常会无限递归）。
 *
 * 长度防御：message 截 2000 字（协议上限，超限服务端整批 400）、stack 截
 * 8000 字符（协议未约束，截断防单条巨堆栈撑爆服务端环形缓冲的 kv 值体积）。
 */

import type { ClientLogEntry } from '@/api/generated'
import { getAuthHeaders } from './api-client'

/** 队列上限：满即 flush（协议单批上限 50，10 条一批留足余量） */
const MAX_QUEUE = 10
/** 定时 flush 间隔（毫秒）：低频错误不至于攒到页面关闭都没发出去 */
const FLUSH_INTERVAL_MS = 30_000
/** 上报端点（协议 POST /api/v1/client-logs） */
const ENDPOINT = '/api/v1/client-logs'
/** message 截断长度（= 协议 maxLength 2000 字） */
const MESSAGE_MAX = 2000
/** stack 截断长度（协议未约束的客户端防御） */
const STACK_MAX = 8000

let queue: ClientLogEntry[] = []
let flushTimer: ReturnType<typeof setTimeout> | null = null
let flushing = false
let installed = false

/** 组装一条 error 级别条目（ts=当前毫秒，page=当前路由路径） */
function entry(message: string, stack?: string): ClientLogEntry {
  return {
    ts: Date.now(),
    level: 'error',
    message: message.slice(0, MESSAGE_MAX),
    page: window.location.pathname,
    ...(stack ? { stack: stack.slice(0, STACK_MAX) } : {}),
  }
}

/** 入队；满 10 条立即 flush，否则确保 30s 定时器在跑 */
function record(e: ClientLogEntry): void {
  queue.push(e)
  if (queue.length >= MAX_QUEUE) {
    void flushClientLogs()
  } else if (flushTimer === null) {
    flushTimer = setTimeout(() => void flushClientLogs(), FLUSH_INTERVAL_MS)
  }
}

/**
 * 发送当前队列（裸 fetch + Bearer，不经过 SDK 拦截器——上报器是旁路
 * 设施，401/网络错误都不该有鉴权重定向之类的副作用）。
 * 导出仅为可测试性；应用代码只需 installClientLogs()。
 */
export async function flushClientLogs(): Promise<void> {
  if (flushing || queue.length === 0) return
  flushing = true
  if (flushTimer !== null) {
    clearTimeout(flushTimer)
    flushTimer = null
  }
  const batch = queue
  queue = [] // 先摘队列再发送：flush 期间新错误落在新队列，不丢不重
  try {
    const res = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...getAuthHeaders() },
      body: JSON.stringify({ events: batch }),
    })
    if (!res.ok) throw new Error(`status ${res.status}`)
  } catch (err) {
    // 静默丢弃（清队列防积压）；只 console.warn 留痕——不得再触发上报（防递归）
    console.warn('[client-logs] 上报失败，已丢弃本批', err)
  } finally {
    flushing = false
    // flush 期间攒下的新错误：满批立刻再发，零头重新挂定时器
    if (queue.length >= MAX_QUEUE) {
      void flushClientLogs()
    } else if (queue.length > 0 && flushTimer === null) {
      flushTimer = setTimeout(() => void flushClientLogs(), FLUSH_INTERVAL_MS)
    }
  }
}

/**
 * 挂载全局异常监听（幂等；main.tsx 应用启动时调用一次）。
 * 两个 handler 内部全 try-catch 吞异常：上报器自身的任何错误都不外抛、
 * 不回流（onerror 递归触发会栈溢出）。
 */
export function installClientLogs(): void {
  if (installed || typeof window === 'undefined') return
  installed = true

  window.onerror = (message, source, lineno, colno, error) => {
    try {
      const text = String(message)
      // 跨源掩码噪音过滤（2026-09-18）：本站全部脚本同源（index.html 仅内联脚本 +
      // 同源 module），浏览器只对「无 crossorigin 的跨源脚本」把 message 掩码成
      // "Script error."——收到该签名且无错误对象 = 浏览器扩展/注入脚本的第三方
      // 噪音，零排障价值不上报（维护页曾积 4 条同类记录）；带堆栈的仍照常上报。
      if (text === 'Script error.' && !error?.stack) return
      const where = source ? ` (${source}:${lineno ?? '?'}:${colno ?? '?'})` : ''
      record(entry(`${text}${where}`, error?.stack))
    } catch {
      /* 上报器自身异常绝不外抛（防递归） */
    }
  }

  window.addEventListener('unhandledrejection', (event) => {
    try {
      const reason = (event as PromiseRejectionEvent).reason
      const isErr = reason instanceof Error
      record(
        entry(
          `UnhandledRejection: ${isErr ? reason.message : String(reason)}`,
          isErr ? reason.stack : undefined,
        ),
      )
    } catch {
      /* 同上 */
    }
  })
}
