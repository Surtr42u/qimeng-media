/**
 * SSE 事件 React 接线层：把 lib/sse.ts 的流式订阅变成可声明的事件回调。
 *
 * 为什么做模块级单连接（引用计数）而非每个调用方一条 SSE：
 * 服务端每连接都广播全部事件——页面各自订阅会造成 N 条重复连接、N 份重复广播；
 * 单连接 + 模块内分发满足"跨模块触发走事件总线"（AI_README 代码卫生约束 7），
 * 组件/页面零成本订阅，连接生命周期由本模块托管。
 */

import { useEffect, useRef } from 'react'
import type {
  LibraryChangedEvent,
  ScanProgressEvent,
  UploadDoneEvent,
} from '@/api/generated'
import {
  EVENT_TOPIC_LIBRARY_CHANGED,
  EVENT_TOPIC_SCAN_PROGRESS,
  EVENT_TOPIC_THUMBNAIL_PROGRESS,
  EVENT_TOPIC_UPLOAD_DONE,
  EVENTS_PATH,
} from '@/lib/constants'
import { subscribeSSE, type SSEMessage } from '@/lib/sse'

/** 页面声明关心的主题回调（未声明 = 忽略该主题） */
export interface SSEEventHandlers {
  /** 扫描进度（walking/reconciling 两阶段计数） */
  onScanProgress?: (event: ScanProgressEvent) => void
  /**
   * 库内容变更（增删改后刷新通知）。
   * 注意 load 形态：广播型触发帧（结束时不带计数）data 为 null，两种形态都容忍。
   */
  onLibraryChanged?: (event: LibraryChangedEvent | null) => void
  /** 上传入库完成（携带 assetId，可精确失效单条而不必刷全列表） */
  onUploadDone?: (event: UploadDoneEvent) => void
  /** 缩略图生成进度——当前无发布者（服务端注释载荷契约待定），收到也忽略；参数位为将来接入预留 */
  onThumbnailProgress?: (event: unknown) => void
  /**
   * 连接（重）建立（每次 HTTP 200 且开始读流都触发，含首次与每次重连成功）。
   * 为什么存在：SSE 无回放/补发，断线窗口（服务重启/代理超时）错过的事件
   * 不可追——主流语义是重连成功即重新校验本地缓存（SseBridge 据此失效根
   * 查询）。首次连接也触发 = 多拉一次，幂等无害。
   */
  onOpen?: () => void
}

/** 模块级单连接的桥接：handler 集合 + 引用计数 */
type BridgeListener = (msg: SSEMessage) => void
const bridgeListeners = new Set<BridgeListener>()
/** 连接建立回调集合（与消息分发并列的独立通道——open 不是一帧消息） */
const bridgeOpenListeners = new Set<() => void>()
let unsubscribeBridge: (() => void) | null = null
let subscribeCount = 0

function ensureConnected(): void {
  if (unsubscribeBridge) return
  unsubscribeBridge = subscribeSSE({
    url: EVENTS_PATH,
    onOpen: () => {
      // 复制快照：listener 可能在回调中被移除（组件卸载），遍历中删除会漏发
      for (const listener of Array.from(bridgeOpenListeners)) listener()
    },
    onMessage: (msg) => {
      // 复制快照：handler 可能在回调中被移除（组件卸载），遍历中删除会漏发
      for (const listener of Array.from(bridgeListeners)) listener(msg)
    },
  })
}

function releaseIfIdle(): void {
  subscribeCount--
  if (subscribeCount <= 0 && unsubscribeBridge) {
    unsubscribeBridge()
    unsubscribeBridge = null
  }
}

/** data 字段 JSON 解析（帧内数据坏：跳过单个事件不打断整条流——服务端 sse.go 同精神） */
function parseEventData<T>(data: string): T | null {
  try {
    return JSON.parse(data) as T
  } catch {
    return null
  }
}

/** 主题分发：topic → 业务回调 */
function dispatch(handlers: SSEEventHandlers, msg: SSEMessage): void {
  switch (msg.event) {
    case EVENT_TOPIC_SCAN_PROGRESS: {
      const event = parseEventData<ScanProgressEvent>(msg.data)
      if (event) handlers.onScanProgress?.(event)
      break
    }
    case EVENT_TOPIC_LIBRARY_CHANGED: {
      // 广播型触发帧 data=null（协议 LibraryChangedEvent 注释）→ 以 null 形态回调；
      // 解析失败 = 帧损坏 → 跳过（客户端容忍单帧损坏，不掉整条流）
      let event: LibraryChangedEvent | null
      try {
        event = JSON.parse(msg.data) as LibraryChangedEvent | null
      } catch {
        break
      }
      handlers.onLibraryChanged?.(event)
      break
    }
    case EVENT_TOPIC_UPLOAD_DONE: {
      const event = parseEventData<UploadDoneEvent>(msg.data)
      if (event) handlers.onUploadDone?.(event)
      break
    }
    case EVENT_TOPIC_THUMBNAIL_PROGRESS: {
      // 无发布者：显式忽略（不回调），仅存档防未来误用
      handlers.onThumbnailProgress?.(msg.data)
      break
    }
    default:
      // 未来新增主题：不阻断，静默忽略（消息协议向后兼容）
      break
  }
}

/**
 * 订阅 SSE 事件：挂载时注册（引用计数 +1，首个订阅者建立连接），卸载时移除。
 * handlers 传引用即可——内部用 ref 实时同步最新回调，避免每次渲染重建订阅。
 * 页面/组件任意层级可调用（管理页订阅 scan.progress、AppShell 的 SseBridge 订阅
 * library.changed/upload.done 生效失效），连接只有一条。
 */
export function useSSEEvents(handlers: SSEEventHandlers): void {
  const handlerRef = useRef(handlers)
  // 用 effect 同步最新回调（而非渲染期写 ref）：渲染期写 ref 会触发
  // React Compiler 优化跳过/不一致渲染（oxlint react(refs)），
  // effect 同步保证订阅 listener 每次 dispatch 读到的是最新 handlers
  useEffect(() => {
    handlerRef.current = handlers
  })

  useEffect(() => {
    const listener: BridgeListener = (msg) => dispatch(handlerRef.current, msg)
    bridgeListeners.add(listener)
    const openListener: () => void = () => handlerRef.current.onOpen?.()
    bridgeOpenListeners.add(openListener)
    subscribeCount++
    ensureConnected()
    return () => {
      bridgeListeners.delete(listener)
      bridgeOpenListeners.delete(openListener)
      releaseIfIdle()
    }
  }, [])
}
