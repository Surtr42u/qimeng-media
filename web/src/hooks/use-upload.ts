/**
 * 上传队列 hook（W-1 文件管理页「上传」卡消费；铁律 7：UI 组件不直接调 API）。
 *
 * 为什么裸 XHR 而非生成 SDK：SDK 走 fetch，拿不到上传字节级进度事件，也无法
 * 按单文件中止——W-1 冻结约束明确 XHR + application/octet-stream 流式 + abort。
 * 端点常量复用 lib/constants.ts 的 UPLOAD_PATH（协议 POST /api/v1/assets/upload，
 * libraryId/dir/filename 走 query、body=原始字节流）；鉴权复用 getAuthHeaders
 * （api-client 注释已预留本文件为裸请求场景）。
 *
 * 口径（W-1 冻结 + 2026-09-05 拍板补充）：
 * - libraryId 必填（协议 required）；目标目录 dir = 库内相对路径（'' = 库根）；
 *   目标在入队时刻快照进条目（item.target），之后切换库/目录只影响新入队的
 *   文件——队列表按快照显示每个文件传去哪（§5 第 10 条缺陷修法）。
 * - 串行 = 全队列一次一路传输，跨批次也不并发（§5 第 11 条①）。
 * - 大小上限前置拦截：上限值来自 GET /config 的 upload.maxBytesMb（设置页实时
 *   生效项），超限文件不入网（中文提示）。类型白名单不在前端复制——服务端
 *   四道校验是唯一口径，4xx 响应体 {code,message} 的 message 原样透传展示。
 * - 取消语义：切路由（组件卸载）自动 abort 整队，不做后台续传。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ASSETS_QUERY_KEY, DIRS_QUERY_KEY, LIBRARIES_QUERY_KEY } from '@/lib/query-keys'
import { getAuthHeaders } from '@/lib/api-client'
import { UPLOAD_PATH } from '@/lib/constants'

/** MB → 字节换算（与服务端 config 口径一致：1MB = 1<<20，见 upload.go kvMax） */
const BYTES_PER_MB = 1 << 20

/** 上传单条状态：queued（排队）→ uploading（传输/服务端处理中）→ 终态三选一 */
export type UploadStatus = 'queued' | 'uploading' | 'done' | 'failed' | 'canceled'

/** 队列单条（渲染口径：文件名/大小/目标/进度条/状态 + 失败原因） */
export interface UploadItem {
  id: string
  name: string
  sizeBytes: number
  status: UploadStatus
  /** 已发送百分比 0–100（XHR progress.loaded/total）；100 不代表完成，响应 2xx 才算 */
  percent: number
  /** 入队时快照的目标库 ID（队列表显示目标用；前置拦截失败的条目没有目标） */
  targetLibraryId?: string
  /** 入队时快照的目标目录（库内相对路径，'' = 库根） */
  targetDir?: string
  /** 失败原因（4xx 透传服务端 message；前置拦截/网络错误为客户端中文文案） */
  errorText?: string
}

export interface UploadQueueOptions {
  /** 目标库 ID（协议必填；空串时整批直接判失败、不发任何请求） */
  libraryId: string
  /** 目标目录（库内相对路径，'/' 分隔；'' = 库根——协议语义原样透传） */
  dir: string
  /** 单文件上限（MB，GET /config upload.maxBytesMb）；null = 配置未就绪不前置拦截（服务端 413 仍兜底） */
  maxBytesMb: number | null
  /** 单文件到达终态的回调（done/failed；toast 在组件层做，hook 不碰 UI） */
  onItemSettled?: (item: UploadItem) => void
}

/** 队列内部条目 = 渲染契约 UploadItem + 传输所需的 File 句柄（file 不进组件层）。
 *  目标快照直接落在 UploadItem.targetLibraryId/targetDir（渲染与发送同源，不双写） */
interface QueueEntry extends UploadItem {
  file: File
}

/** 模块级序号：队列条目 id（页面生命周期内唯一即可，不进协议） */
let uploadSeq = 0

/**
 * 上传队列（严格串行：全队列一次只有一路传输在跑——包括"上一批没传完又拖入
 * 新一批"的场景，新条目由同一泵顺次拾取，绝不两路并发；协议未约定并发度，
 * 这是客户端自行约束的最保守取值。NAS 磁盘/网带宽友好，进度条逐条可读）。
 * itemsRef 是权威副本（XHR 回调绕过 React 批处理写状态，闭包读 state 会拿到
 * 旧值），setState 只做渲染镜像；组件卸载 effect 清理时掐断整队（在传 abort +
 * 排队标 canceled，不做后台续传——W-1 冻结）。
 */
export function useUploadQueue(options: UploadQueueOptions) {
  const [items, setItemsState] = useState<UploadItem[]>([])
  const itemsRef = useRef<QueueEntry[]>([])
  const xhrsRef = useRef(new Map<string, XMLHttpRequest>())
  // 串行泵在跑标志：enqueue 只负责把条目写进权威副本再唤泵，泵自身保证单路
  const drainingRef = useRef(false)
  // 回调经 ref 转发：调用方传内联函数也不重启任何东西（模式同 use-sse-events）
  const optionsRef = useRef(options)
  useEffect(() => {
    optionsRef.current = options
  })

  const queryClient = useQueryClient()

  /** 权威副本变更 + 渲染镜像同步（唯一的写入口，保证两者不漂移） */
  const writeItems = useCallback((updater: (prev: QueueEntry[]) => QueueEntry[]) => {
    itemsRef.current = updater(itemsRef.current)
    setItemsState(itemsRef.current)
  }, [])

  const patchItem = useCallback(
    (id: string, patch: Partial<UploadItem>) => {
      writeItems((prev) => prev.map((it) => (it.id === id ? { ...it, ...patch } : it)))
    },
    [writeItems],
  )

  /** 上传入库成功后的本地失效（SseBridge 只覆盖其他端上传的场景——其注释约定本 hook 负责本页失效）；
   *  资产族按根键失效：除列表外，类型徽标 total/筛选 facets 也随新文件变化 */
  const invalidateUploaded = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ASSETS_QUERY_KEY })
    void queryClient.invalidateQueries({ queryKey: LIBRARIES_QUERY_KEY })
    void queryClient.invalidateQueries({ queryKey: DIRS_QUERY_KEY })
  }, [queryClient])

  /** 4xx/5xx 响应体透传：服务端 {code,message} 的 message 是唯一口径；解析失败兜底状态码文案 */
  const serverErrorText = (xhr: XMLHttpRequest): string => {
    try {
      const parsed = JSON.parse(xhr.responseText) as { message?: string }
      if (parsed.message) return parsed.message
    } catch {
      // 非 JSON（代理页/空体）：落状态码兜底
    }
    return `上传失败（HTTP ${xhr.status}）`
  }

  /** 发送单条；resolve 于终态（done/failed/canceled），供队列顺序推进。
   *  目标只认入队快照（item.targetLibraryId/targetDir）——发送途中用户切换
   *  库/目录不影响已排队条目（§5 第 10 条修法）。 */
  const uploadOne = useCallback(
    (item: QueueEntry) =>
      new Promise<void>((resolve) => {
        const params = new URLSearchParams({
          libraryId: item.targetLibraryId ?? '',
          dir: item.targetDir ?? '',
          filename: item.name,
        })
        const xhr = new XMLHttpRequest()
        xhrsRef.current.set(item.id, xhr)
        xhr.open('POST', `${UPLOAD_PATH}?${params.toString()}`)
        for (const [key, value] of Object.entries(getAuthHeaders())) {
          xhr.setRequestHeader(key, value)
        }
        // 流式约定（协议 requestBody content-type）：File 对象直传，浏览器分片发送
        xhr.setRequestHeader('Content-Type', 'application/octet-stream')

        xhr.upload.onprogress = (e) => {
          if (!e.lengthComputable) return
          patchItem(item.id, { percent: Math.round((e.loaded / e.total) * 100) })
        }

        const settle = (patch: Partial<UploadItem>): void => {
          xhrsRef.current.delete(item.id)
          patchItem(item.id, patch)
          const final = itemsRef.current.find((it) => it.id === item.id)
          // 只有 done/failed 通知（canceled 是用户主动行为，不再弹 toast打扰）
          if (final && (final.status === 'done' || final.status === 'failed')) {
            optionsRef.current.onItemSettled?.(final)
          }
          resolve()
        }

        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            invalidateUploaded()
            settle({ status: 'done', percent: 100 })
          } else {
            settle({ status: 'failed', errorText: serverErrorText(xhr) })
          }
        }
        xhr.onerror = () => settle({ status: 'failed', errorText: '网络错误，上传中断' })
        xhr.onabort = () => settle({ status: 'canceled' })
        xhr.send(item.file)
      }),
    [invalidateUploaded, patchItem],
  )

  /**
   * 串行泵：循环拾取权威副本里的排队条目逐条发送，直到没有排队态。
   * drainingRef 保证全 hook 生命周期最多一个泵在跑——后入队的批次不另起
   * 第二路（§5 第 11 条①「跨批并发」缺陷的修法），只会被在跑泵顺次拾取。
   */
  const pump = useCallback(async () => {
    if (drainingRef.current) return
    drainingRef.current = true
    try {
      for (;;) {
        const next = itemsRef.current.find((it) => it.status === 'queued')
        if (!next) break
        patchItem(next.id, { status: 'uploading' })
        await uploadOne(next)
      }
    } finally {
      drainingRef.current = false
    }
  }, [patchItem, uploadOne])

  const enqueue = useCallback(
    (files: File[]) => {
      const opts = optionsRef.current
      const created: QueueEntry[] = files
        .filter((f) => f.name !== '')
        .map((f): QueueEntry => ({
          id: `upload-${++uploadSeq}`,
          name: f.name,
          sizeBytes: f.size,
          status: 'queued',
          percent: 0,
          // 目标此刻定格（渲染字段即发送字段）：批量上传途中切换库/目录只影响之后新入队的文件
          targetLibraryId: opts.libraryId,
          targetDir: opts.dir,
          file: f,
        }))
      if (created.length === 0) return

      // libraryId 协议必填：未选库整批拦截（组件层已禁用入口，这里是防御兜底）
      if (opts.libraryId === '') {
        const blocked = created.map((it) => ({ ...it, status: 'failed' as const, errorText: '请先选择目标库' }))
        writeItems((prev) => [...prev, ...blocked])
        for (const it of blocked) optionsRef.current.onItemSettled?.(it)
        return
      }
      // 大小上限前置拦截（冻结口径：读 GET /config upload 项，超限不上网，中文提示）
      const limitMb = opts.maxBytesMb
      const checked =
        limitMb != null
          ? created.map((it) =>
              it.sizeBytes > limitMb * BYTES_PER_MB
                ? { ...it, status: 'failed' as const, errorText: `文件超过大小上限 ${limitMb} MB，已拦截` }
                : it,
            )
          : created
      writeItems((prev) => [...prev, ...checked])
      for (const it of checked) {
        if (it.status === 'failed') optionsRef.current.onItemSettled?.(it)
      }
      void pump()
    },
    [pump, writeItems],
  )

  /** 取消整队：在传的 abort（onabort 标 canceled），排队的直接标 canceled */
  const cancelAll = useCallback(() => {
    for (const xhr of xhrsRef.current.values()) xhr.abort()
    writeItems((prev) => prev.map((it) => (it.status === 'queued' ? { ...it, status: 'canceled' } : it)))
  }, [writeItems])

  /** 清除终态行（在传/排队的不动） */
  const clearFinished = useCallback(() => {
    writeItems((prev) => prev.filter((it) => it.status === 'queued' || it.status === 'uploading'))
  }, [writeItems])

  // 切路由自动 abort 整队（W-1 冻结：不做后台续传）——在传与排队必须一起掐断：
  // 只 abort 在传 XHR 的话，串行泵发完在传条后会继续拾取 queued 条目后台续传。
  // 排队条目先标 canceled（泵拾取自然落空退出），再 abort 在传（onabort 标 canceled）。
  // 卸载后组件状态随之丢弃，writeItems 只是收尾同步渲染镜像。
  useEffect(
    () => () => {
      writeItems((prev) => prev.map((it) => (it.status === 'queued' ? { ...it, status: 'canceled' } : it)))
      for (const xhr of xhrsRef.current.values()) xhr.abort()
    },
    [writeItems],
  )

  const isBusy = items.some((it) => it.status === 'queued' || it.status === 'uploading')
  const hasFinished = items.some(
    (it) => it.status === 'done' || it.status === 'failed' || it.status === 'canceled',
  )

  return { items, enqueue, cancelAll, clearFinished, isBusy, hasFinished }
}
