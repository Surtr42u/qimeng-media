/**
 * 上传队列 hook（文件管理页上传工作台消费；铁律 7：UI 组件不直接调 API）。
 *
 * 为什么裸 XHR 而非生成 SDK：SDK 走 fetch，拿不到上传字节级进度事件，也无法
 * 按单文件中止——W-1 冻结约束明确 XHR + application/octet-stream 流式 + abort。
 * 端点常量复用 lib/constants.ts 的 UPLOAD_PATH（协议 POST /api/v1/assets/upload，
 * libraryId/dir/filename 走 query、body=原始字节流——query 组装口径单一来源
 * lib/upload-params.ts）；鉴权复用 getAuthHeaders（api-client 注释已预留本文件为裸请求场景）。
 *
 * 口径（W-1 冻结 + 2026-09-25 拍板补充 + 2026-09-28 暂存区重做对齐 App）：
 * - 目标快照：libraryId/dir 在「开始上传」时刻随 enqueue 调用定格进条目
 *   （item.targetLibraryId/targetDir），之后切换批次库/目录只影响下一批。
 *   拖拽目录条目的 dir 由 relativePath 纯函数换算（lib/folder-upload.ts，
 *   目录段拼到批次 dir 下），条目级覆盖批次值；不安全路径的条目入队即拦。
 * - 落库名：条目可编辑「基名」（扩展名锁定，lib/upload-naming 单一拼装口径），
 *   filename 取编辑后的完整落库名，未编辑回退原文件名（同 App
 *   StagedUpload.effectiveUploadName 语义）。
 * - 自动挂靠（REQ-上传指定作者与来源）：201 之后先 PUT /assets/{id}/authors
 *   单项整体替换（新资产零关联，传单项安全）再 PUT /authors/{authorId}/sources
 *   mode=append（并入去重永不覆盖既有来源）——顺序固定，与 App UploadAttacher
 *   同款；挂靠失败不回滚上传（文件已入库），落 attach-failed 专项态，绝不重试
 *   （重试 = 重复上传文件）。
 * - 串行 = 全队列一次一路传输，跨批次也不并发（§5 第 11 条①）；挂靠完成才
 *   拾取下一条（挂靠序与上传序一致）。
 * - 大小上限前置拦截：上限值来自 GET /config 的 upload.maxBytesMb（设置页实时
 *   生效项），超限文件不入网（中文提示）。类型白名单不在前端复制——服务端
 *   四道校验是唯一口径，4xx 响应体 {code,message} 的 message 原样透传展示。
 * - 取消语义：切路由（组件卸载）自动 abort 整队，不做后台续传。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  putApiV1AssetsByAssetIdAuthors,
  putApiV1AuthorsByAuthorIdSources,
} from '@/api/generated'
import { ASSETS_QUERY_KEY, AUTHORS_QUERY_KEY, DIRS_QUERY_KEY, LIBRARIES_QUERY_KEY } from '@/lib/query-keys'
import { getAuthHeaders, unwrapSdkResult } from '@/lib/api-client'
import { UPLOAD_PATH } from '@/lib/constants'
import { batchFailureReason } from '@/lib/batch'
import { composeUploadName, extensionOf } from '@/lib/upload-naming'
import { uploadTargetFromRelativePath } from '@/lib/folder-upload'
import { buildUploadQuery } from '@/lib/upload-params'

/** MB → 字节换算（与服务端 config 口径一致：1MB = 1<<20，见 upload.go kvMax）。
 *  组件层「开始上传」超限前置拦截与 hook 内兜底拦截共用本常量，禁止散抄 */
export const BYTES_PER_MB = 1 << 20

/** 上传单条状态：queued（排队）→ uploading（传输/服务端处理中）→ 终态四选一。
 *  attach-failed = 文件已入库但自动挂靠失败（不回滚上传、不重试，专项态提示） */
export type UploadStatus = 'queued' | 'uploading' | 'done' | 'failed' | 'canceled' | 'attach-failed'

/** 201 后自动挂靠载荷（作者必填、来源可选；来源挂靠以作者块为前提，服务端口径） */
export interface UploadAttach {
  authorId: string
  /** 作者展示名（纯 UI 展示；服务端只认 authorId） */
  authorName: string
  /** 并入作者来源区的来源词（mode=append；空数组 = 不动来源） */
  sources: string[]
}

/** 入队载荷：暂存条目在「开始上传」时刻的形态（file 句柄只在 hook 内消费） */
export interface StagedFileInput {
  file: File
  /** 拖拽目录条目的相对路径（undefined = 点击选择，无子目录段） */
  relativePath?: string
  /** 编辑后的落库基名（未编辑 = undefined，回退原文件名；扩展名锁定在 hook 内拼接） */
  uploadBaseName?: string
  /** 201 后自动挂靠（undefined = 不挂靠，行为与留空上传完全一致） */
  attach?: UploadAttach
}

/** 队列单条（渲染口径：文件名/大小/目标/挂靠/进度条/状态 + 失败原因） */
export interface UploadItem {
  id: string
  /** 落库名（编辑基名 + 锁定扩展名拼装后的实际 filename 参数值） */
  name: string
  /** 原文件名（仅编辑过基名时存在，队列行「原文件名」展示用） */
  originalName?: string
  sizeBytes: number
  status: UploadStatus
  /** 已发送百分比 0–100（XHR progress.loaded/total）；100 不代表完成，响应 2xx 才算 */
  percent: number
  /** 入队时快照的目标库 ID（队列表显示目标用；前置拦截失败的条目没有目标） */
  targetLibraryId?: string
  /** 入队时快照的目标目录（库内相对路径，'' = 库根；拖拽目录条目=批次目录+拖入子目录段） */
  targetDir?: string
  /** 201 后自动挂靠载荷（入队快照；undefined = 该条不带挂靠） */
  attach?: UploadAttach
  /** 失败原因（4xx 透传服务端 message；挂靠失败为挂靠环节文案；网络错误为客户端中文文案） */
  errorText?: string
}

export interface UploadQueueOptions {
  /** 单文件上限（MB，GET /config upload.maxBytesMb）；null = 配置未就绪不前置拦截（服务端 413 仍兜底） */
  maxBytesMb: number | null
  /** 单文件到达终态的回调（done/failed/attach-failed；toast 在组件层做，hook 不碰 UI） */
  onItemSettled?: (item: UploadItem) => void
}

/** enqueue 的批次目标（调用时刻快照进每一条；之后切换只影响下一批） */
export interface UploadBatchTarget {
  /** 目标库 ID（协议必填；空串时整批直接判失败、不发任何请求） */
  libraryId: string
  /** 目标目录（库内相对路径，'/' 分隔；'' = 库根——协议语义原样透传） */
  dir: string
}

/** 队列内部条目 = 渲染契约 UploadItem + 传输所需的 File 句柄（file 不进组件层）。
 *  目标快照直接落在 UploadItem.targetLibraryId/targetDir（渲染与发送同源，不双写） */
interface QueueEntry extends UploadItem {
  file: File
}

/** 模块级序号：队列条目 id（页面生命周期内唯一即可，不进协议） */
let uploadSeq = 0

/**
 * 201 响应体 → 资产 id（挂靠目标）。协议 201 必回 AssetDetail；解析失败返回
 * null（挂靠缺目标，落 attach-failed 专项态——文件已入库，绝不因此重传）。
 */
function parseUploadedAssetId(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { id?: unknown }
    return typeof parsed.id === 'string' && parsed.id !== '' ? parsed.id : null
  } catch {
    return null
  }
}

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
   *  资产族按根键失效：除列表外，类型徽标 total/筛选 facets 也随新文件变化；
   *  作者族一并失效：新文件挂靠/来源并入改写作者 fileCount 与来源区 */
  const invalidateUploaded = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ASSETS_QUERY_KEY })
    void queryClient.invalidateQueries({ queryKey: LIBRARIES_QUERY_KEY })
    void queryClient.invalidateQueries({ queryKey: DIRS_QUERY_KEY })
    void queryClient.invalidateQueries({ queryKey: AUTHORS_QUERY_KEY })
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

  /**
   * 自动挂靠序列（同 App UploadAttacher）：先 PUT /assets/{id}/authors 单项
   * 整体替换（新上传资产零关联、传单项安全），后 PUT /authors/{authorId}/sources
   * mode=append（并入去重永不覆盖既有来源）——顺序固定（authors 可能触发服务端
   * 建作者块，sources append 依赖块存在）。返回 null = 全部挂上；否则为失败文案。
   * 走生成 SDK（挂靠无进度诉求，token/baseUrl 随全局 client）；任何失败不重试。
   */
  const attachSequence = useCallback(
    async (assetId: string, attach: UploadAttach): Promise<string | null> => {
      try {
        await unwrapSdkResult(
          putApiV1AssetsByAssetIdAuthors({
            path: { assetId },
            body: { authorIds: [attach.authorId] },
          }),
        )
        if (attach.sources.length > 0) {
          await unwrapSdkResult(
            putApiV1AuthorsByAuthorIdSources({
              path: { authorId: attach.authorId },
              body: { sources: attach.sources, mode: 'append' },
            }),
          )
        }
        return null
      } catch (err) {
        return batchFailureReason(err)
      }
    },
    [],
  )

  /** 发送单条；resolve 于终态（done/failed/attach-failed/canceled），供队列顺序推进。
   *  目标只认入队快照（item.targetLibraryId/targetDir）——发送途中用户切换
   *  库/目录不影响已排队条目（§5 第 10 条修法；拖拽目录条目的 dir 亦为
   *  入队时刻由 relativePath 换算定格）。 */
  const uploadOne = useCallback(
    (item: QueueEntry) =>
      new Promise<void>((resolve) => {
        // query 组装收敛在 lib/upload-params.ts 纯函数（行为由测试锁定）
        const params = buildUploadQuery(
          item.targetLibraryId ?? '',
          item.targetDir ?? '',
          item.name,
        )
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
          // done/failed/attach-failed 通知（canceled 是用户主动行为，不再弹 toast打扰）
          if (final && final.status !== 'queued' && final.status !== 'uploading' && final.status !== 'canceled') {
            optionsRef.current.onItemSettled?.(final)
          }
          resolve()
        }

        /** 201 → 挂靠序列 → 终态（挂靠完成才 resolve，串行泵才拾取下一条） */
        const attachThenSettle = async (body: string): Promise<void> => {
          const attach = item.attach
          if (attach === undefined || attach.authorId === '') {
            settle({ status: 'done', percent: 100 })
            return
          }
          const assetId = parseUploadedAssetId(body)
          if (assetId === null) {
            settle({
              status: 'attach-failed',
              percent: 100,
              errorText: '文件已入库，但响应缺少资产 ID，未能自动挂靠',
            })
            return
          }
          const attachErr = await attachSequence(assetId, attach)
          if (attachErr === null) settle({ status: 'done', percent: 100 })
          else settle({ status: 'attach-failed', percent: 100, errorText: `文件已入库，但挂靠失败：${attachErr}` })
        }

        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            invalidateUploaded()
            void attachThenSettle(xhr.responseText)
          } else {
            settle({ status: 'failed', errorText: serverErrorText(xhr) })
          }
        }
        xhr.onerror = () => settle({ status: 'failed', errorText: '网络错误，上传中断' })
        xhr.onabort = () => settle({ status: 'canceled' })
        xhr.send(item.file)
      }),
    [attachSequence, invalidateUploaded, patchItem],
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

  /**
   * 入队（目标此刻随批次参数定格，渲染字段即发送字段）：开始上传时刻调用，
   * 批次库/目录为全体条目的目标快照；条目编辑基名时 filename =
   * 基名+锁定扩展名（lib/upload-naming 单一拼装口径），未编辑回退原文件名。
   * 条目两种形态——点击选择（无 relativePath）与拖拽收集（dir 由 relativePath
   * 换算到批次 dir 之下；换算出 null = 不安全路径，该条目入队即拦不出网）。
   */
  const enqueue = useCallback(
    (inputs: readonly StagedFileInput[], batch: UploadBatchTarget) => {
      const created: QueueEntry[] = inputs
        .filter((it) => it.file.name !== '')
        .map((it): QueueEntry => {
          // 目标此刻定格（渲染字段即发送字段）；relativePath 条目的 dir 在
          // 批次 dir 下逐段拼接（lib/folder-upload.ts 纯函数，null=不安全路径）
          const target = it.relativePath !== undefined
            ? uploadTargetFromRelativePath(it.relativePath, batch.dir)
            : { dir: batch.dir, filename: it.file.name }
          const originalName = target === null ? (it.relativePath ?? it.file.name) : target.filename
          // 落库名单一口径：编辑基名 + 锁定扩展名（lib/upload-naming），空基名回退原名
          const edited = it.uploadBaseName !== undefined && it.uploadBaseName.trim() !== ''
            ? composeUploadName(it.uploadBaseName, extensionOf(originalName))
            : ''
          const uploadName = edited !== '' ? edited : originalName
          return {
            id: `upload-${++uploadSeq}`,
            name: uploadName,
            originalName: uploadName !== originalName ? originalName : undefined,
            sizeBytes: it.file.size,
            status: target === null ? 'failed' : 'queued',
            percent: 0,
            targetLibraryId: batch.libraryId,
            targetDir: target === null ? undefined : target.dir,
            attach: it.attach,
            errorText: target === null ? '拖入路径不安全（含 .. 或绝对路径），已拦截' : undefined,
            file: it.file,
          }
        })
      if (created.length === 0) return

      // libraryId 协议必填：未选库整批拦截（组件层门禁已拦，这里是防御兜底）
      if (batch.libraryId === '') {
        const blocked = created.map((it) => ({ ...it, status: 'failed' as const, errorText: '请先选择目标库' }))
        writeItems((prev) => [...prev, ...blocked])
        for (const it of blocked) optionsRef.current.onItemSettled?.(it)
        return
      }
      // 大小上限前置拦截（冻结口径：读 GET /config upload 项，超限不上网，中文提示）
      const limitMb = optionsRef.current.maxBytesMb
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
    (it) =>
      it.status === 'done' || it.status === 'failed' || it.status === 'attach-failed' || it.status === 'canceled',
  )

  return { items, enqueue, cancelAll, clearFinished, isBusy, hasFinished }
}
