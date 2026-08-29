/**
 * SSE（Server-Sent Events）纯逻辑层：帧解析与 fetch 流式订阅。
 *
 * 为什么不用 EventSource：服务端 SSE 端点要求 Bearer 鉴权（见业务事实），
 * EventSource 无法带自定义 Header，必须用 fetch 流式 + 手动解析帧。
 *
 * 本文件不关心具体事件主题（hooks/use-sse-events.ts 负责把消息翻译成业务回调），
 * 职责边界：连接管理（auth/重连/取消）+ WHATWG 帧语法解析。
 */

import { emitAuthFailed, getAuthHeaders } from './api-client'
import {
  EVENTS_PATH,
  SSE_MAX_RECONNECT_DELAY_MS,
  SSE_MIN_RECONNECT_DELAY_MS,
  SSE_RECONNECT_DELAY_MS,
} from './constants'

/** 一个解析完成的 SSE 帧（WHATWG：event/data 多行 data 拼接，id/retry 可选） */
export interface SSEMessage {
  /** 事件名（服务端用协议主题：scan.progress / library.changed / upload.done / thumbnail.progress） */
  event: string
  /** data 载荷原文（多行 data: 按 \n 拼接还原；JSON 解析由消费方负责） */
  data: string
  /** 服务端事件游标（server/internal/events/sse.go 输出 id: 字段） */
  id?: string
  /** retry: 帧指定的重连延迟（ms），只对下一次断线生效（WHATWG 规范） */
  retry?: number
}

/** 增量 SSE 帧解析器：每个 chunk 喂入，产出完整帧。
 * 为什么做成有状态解析器而非一次 parse：fetch 流式分块边界与帧边界无关——
 * 一个帧可能被拆进两个 chunk，或一个 chunk 含多个帧。
 */
export function createSSEParser(): (chunk: string) => SSEMessage[] {
  let pendingLine = '' // 上一个 chunk 末尾未结束的行
  let frame: { event?: string; dataLines: string[]; id?: string; retry?: number } | null = null

  return (chunk: string): SSEMessage[] => {
    const messages: SSEMessage[] = []
    const lines = (pendingLine + chunk).split('\n')
    // 最后一个"行"可能不完整（没有换行符），留给下一个 chunk 拼接
    pendingLine = lines.pop() ?? ''

    for (const rawLine of lines) {
      // \r 防御：网络层可能带 CRLF（fetch 不保证剥掉，W3C 规范要求按 \r\n 处理）
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine

      if (line === '') {
        // 空行 = 帧结束；只有 event/data 都为空才丢弃（纯心跳帧）
        if (frame && (frame.event !== undefined || frame.dataLines.length > 0)) {
          messages.push({
            event: frame.event ?? 'message', // WHATWG：无 event 字段事件名为 message
            data: frame.dataLines.join('\n'),
            ...(frame.id !== undefined ? { id: frame.id } : {}),
            ...(frame.retry !== undefined ? { retry: frame.retry } : {}),
          })
        }
        frame = null
        continue
      }

      // 注释行（服务端未来可能发 ": keepalive" 心跳注释，必须容忍）
      if (line.startsWith(':')) continue

      // WHATWG 字段语法：`field: value`；无冒号按全字段处理（value 为 ''）
      const colon = line.indexOf(':')
      const field = colon === -1 ? line : line.slice(0, colon)
      let value = colon === -1 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)

      if (!frame) frame = { dataLines: [] }
      switch (field) {
        case 'event':
          frame.event = value
          break
        case 'data':
          frame.dataLines.push(value)
          break
        case 'id':
          frame.id = value
          break
        case 'retry': {
          const n = Number(value)
          if (Number.isFinite(n) && n > 0) frame.retry = n
          break
        }
        default:
          // 未知字段忽略（WHATWG 要求，向前兼容）
          break
      }
    }
    return messages
  }
}

/** 订阅选项 */
export interface SubscribeSSEOptions {
  url: string
  /** 消息帧回调（topic 分发由调用方做） */
  onMessage: (msg: SSEMessage) => void
  /** 连接建立（HTTP 200 且已开始读取）时回调 */
  onOpen?: () => void
  /** 错误回调（非致命：重连逻辑不受影响；鉴权失败除外） */
  onError?: (error: unknown) => void
}

/**
 * 订阅 SSE 流（fetch 流式 + 自动重连）。
 * @returns 取消函数：调用后中止 fetch/定时器并停止重连（组件卸载时必须调用）。
 *
 * 重连策略（为什么无限重连）：媒体库首页/相册靠 SSE 获得"扫描完成/上传完成"通知，
 * 中途断开（服务端重启、网络闪断、代理超时）后必须自动恢复，否则 UI 会停留在旧状态。
 * 延迟取 retry: 帧值（夹在 [SSE_MIN_, SSE_MAX_]），无帧时用 SSE_RECONNECT_DELAY_MS。
 */
export function subscribeSSE(options: SubscribeSSEOptions): () => void {
  let cancelled = false
  let controller: AbortController | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectDelay = SSE_RECONNECT_DELAY_MS
  let lastRetry: number | null = null

  const cancel = (): void => {
    cancelled = true
    controller?.abort()
    if (retryTimer !== null) clearTimeout(retryTimer)
  }

  const connect = async (): Promise<void> => {
    controller = new AbortController()
    try {
      // Accept 必须显式声明：不加时防火墙/代理可能缓冲响应而非流式返回
      const response = await fetch(options.url, {
        headers: { ...getAuthHeaders(), Accept: 'text/event-stream' },
        signal: controller.signal,
      })

      if (!response.ok) {
        const status = response.status
        if (status === 401 || status === 403) {
          // SSE 是裸 fetch，不走 api-client 拦截器，这里手动广播鉴权失败让 AuthGate 接管；
          // 同时停止重连——token 已失效，重连只会空转
          emitAuthFailed()
          options.onError?.(new Error(`SSE 鉴权失败（HTTP ${status}）`))
          return
        }
        throw new Error(`SSE HTTP ${status}`)
      }
      if (!response.body) throw new Error('SSE 响应无 body')

      options.onOpen?.()
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      const parse = createSSEParser()

      for (;;) {
        const { done, value } = await reader.read()
        if (done) break // 服务端主动关流：走重连
        const messages = parse(decoder.decode(value, { stream: true }))
        for (const message of messages) {
          if (message.retry !== undefined && message.retry !== lastRetry) {
            lastRetry = message.retry
            reconnectDelay = Math.min(
              Math.max(message.retry, SSE_MIN_RECONNECT_DELAY_MS),
              SSE_MAX_RECONNECT_DELAY_MS,
            )
          }
          options.onMessage(message)
        }
      }
    } catch (error) {
      if (cancelled) return // 主动取消（AbortError）不算错误
      options.onError?.(error)
    }

    if (cancelled) return
    retryTimer = setTimeout(() => void connect(), reconnectDelay)
  }

  void connect()
  return cancel
}

/** 事件流端点常量导出：use-sse-events 默认订阅路径（协议 GET /api/v1/events） */
export const DEFAULT_EVENTS_URL = EVENTS_PATH
