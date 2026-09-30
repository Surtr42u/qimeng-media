/**
 * 上传队列模块级单例 store（React 生命周期之外的传输状态机；状态机+传输全在
 * 本文件，hooks/use-upload.ts 只是订阅层，组件只渲染不碰传输——ADR-0008）。
 *
 * 为什么模块级单例（2026-10-01 W-1 冻结解冻）：原实现队列与 XHR 句柄全活在
 * hook 的 useRef，切路由（组件卸载）即 abort 整队——用户传大文件中途点去
 * 其它页，在传 abort + 排队全标 canceled，几 GB 白传。W-1「上传是次要功能」
 * 的论证已随上传成为主通道失效（近期连续多批加强：直传化/自动挂靠/目录
 * 递归），冻结解除。现行为：队列随模块存活，全页会话有效；路由切换/组件
 * 卸载不触发任何取消，abort 只发生在用户显式「全部取消」（cancelAll）。
 * 认证失败保持既有语义不变：401/403 条目按失败落终态（服务端 message 透传）、
 * 泵继续拾取下一条，不做整队 abort（挂靠请求走生成 SDK，其 401 经全局拦截器
 * 广播 onAuthFailed 由 AuthGate 接管——api-client 既有路径）。
 * 页面刷新仍会丢队列——浏览器语义（XHR 句柄不跨页面存活）；刷新存活需
 * IndexedDB 持久化，另立项（2026-10-01 记档；同会话内的弱网中断续传已随
 * ADR-0028 分片通道落地，见 upload-chunked.ts——跨页面存活是另一个问题）。
 *
 * 订阅-通知（纯 TS 零新依赖）：状态写入口统一 notify，React 侧
 * useSyncExternalStore 订阅（api-client token store 同款模式）。
 * 严格串行口径（全队列一次一路、跨批不并发）与挂靠序列（同 App
 * UploadAttacher）不变，注释随实现就地保留。
 *
 * 传输通道（ADR-0028 Web 接入批）：按 size 阈值分流——<16MB 走本文件直传
 *（现状 XHR 一字未动），≥16MB 走 lib/upload-chunked.ts 分片会话流（断点续传
 * 状态机全在该文件，选型记档亦在其文件头）；两条通道的成功后处理（入库失效
 * + 挂靠序列）与终态语义完全共用。
 */

import {
  putApiV1AssetsByAssetIdAuthors,
  putApiV1AuthorsByAuthorIdSources,
} from '@/api/generated'
import type { QueryClient } from '@tanstack/react-query'
import {
  ASSETS_QUERY_KEY,
  AUTHORS_QUERY_KEY,
  DIRS_QUERY_KEY,
  LIBRARIES_QUERY_KEY,
} from '@/lib/query-keys'
import { getAuthHeaders, unwrapSdkResult } from '@/lib/api-client'
import { UPLOAD_PATH } from '@/lib/constants'
import { batchFailureReason } from '@/lib/batch'
import { composeUploadName, extensionOf } from '@/lib/upload-naming'
import { uploadTargetFromRelativePath } from '@/lib/folder-upload'
import { buildUploadQuery } from '@/lib/upload-params'
import { runChunkedUpload, useChunkedSession } from '@/lib/upload-chunked'

/** MB → 字节换算（与服务端 config 口径一致：1MB = 1<<20，见 upload.go kvMax）。
 *  组件层「开始上传」超限前置拦截与 store 内兜底拦截共用本常量，禁止散抄 */
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

/** 入队载荷：选文件时刻的形态（file 句柄只在 store 内消费） */
export interface StagedFileInput {
  file: File
  /** 拖拽目录条目的相对路径（undefined = 点击选择，无子目录段） */
  relativePath?: string
  /** 编辑后的落库基名（未编辑 = undefined，回退原文件名；扩展名锁定在 store 内拼接） */
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

/** enqueue 的批次目标（调用时刻快照进每一条；之后切换只影响下一批） */
export interface UploadBatchTarget {
  /** 目标库 ID（协议必填；空串时整批直接判失败、不发任何请求） */
  libraryId: string
  /** 目标目录（库内相对路径，'/' 分隔；'' = 库根——协议语义原样透传） */
  dir: string
}

/** store 运行选项（订阅层 hook 经 setOptions 实时同步；卸载后保留最后一次值——
 *  后台在传队列继续按既有配置与回调工作） */
export interface UploadQueueOptions {
  /** 单文件上限（MB，GET /config upload.maxBytesMb）；null = 配置未就绪不前置拦截（服务端 413 仍兜底） */
  maxBytesMb: number | null
  /** 单条到达终态的回调（done/failed/attach-failed；toast 在组件层做，store 不碰 UI） */
  onItemSettled?: (item: UploadItem) => void
}

/** 队列内部条目 = 渲染契约 UploadItem + 传输所需的 File 句柄（file 不进组件层）。
 *  目标快照直接落在 UploadItem.targetLibraryId/targetDir（渲染与发送同源，不双写） */
interface QueueEntry extends UploadItem {
  file: File
}

// ---- 模块级单例状态（唯一权威副本；React 侧只读镜像经 subscribe/getItems）----

/** 权威队列副本（XHR 回调绕过 React 批处理直接写这里，闭包读快照不会拿旧值） */
let entries: QueueEntry[] = []
/** 在传条目的 XHR 句柄（cancelAll abort 用；settle 时摘除） */
const xhrs = new Map<string, XMLHttpRequest>()
/** 已被 cancelAll 立旗的条目（分片通道在请求间隙——退避等待/相邻请求间——
 *  由 runChunkedUpload 循环顶消费；settle 时清理。直传条目 abort 即达意，
 *  不消费旗标） */
const canceledIds = new Set<string>()
/** 串行泵在跑标志：enqueue 只负责把条目写进权威副本再唤泵，泵自身保证单路 */
let draining = false
/** 队列条目 id 序号（页面生命周期内唯一即可，不进协议） */
let uploadSeq = 0
/** 订阅者集合（useSyncExternalStore 的 listener） */
const listeners = new Set<() => void>()
let options: UploadQueueOptions = { maxBytesMb: null }
/** TanStack Query 客户端引用（订阅层挂载时注入）：上传入库后的本地失效在
 *  组件树外执行——store 跨路由存活，成功回调必须不依赖工作台挂载。
 *  enqueue 唯一入口是订阅层消费组件，故任何入队前必然已注入（防御性判空） */
let queryClientRef: QueryClient | null = null

function notify(): void {
  for (const listener of listeners) listener()
}

/** 权威副本变更的唯一写入口（替换数组引用——useSyncExternalStore 靠引用
 *  变化感知快照更新，原地 mutate 会漏通知） */
function writeItems(updater: (prev: QueueEntry[]) => QueueEntry[]): void {
  entries = updater(entries)
  notify()
}

function patchItem(id: string, patch: Partial<UploadItem>): void {
  writeItems((prev) => prev.map((it) => (it.id === id ? { ...it, ...patch } : it)))
}

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

/** 4xx/5xx 响应体透传：服务端 {code,message} 的 message 是唯一口径；解析失败兜底状态码文案 */
function serverErrorText(xhr: XMLHttpRequest): string {
  try {
    const parsed = JSON.parse(xhr.responseText) as { message?: string }
    if (parsed.message) return parsed.message
  } catch {
    // 非 JSON（代理页/空体）：落状态码兜底
  }
  return `上传失败（HTTP ${xhr.status}）`
}

/** 上传入库成功后的本地失效（SseBridge 只覆盖其他端上传的场景——其注释约定
 *  本 store 负责本端失效）；资产族按根键失效：除列表外，类型徽标 total/筛选
 *  facets 也随新文件变化；作者族一并失效：新文件挂靠/来源并入改写作者
 *  fileCount 与来源区 */
function invalidateUploaded(): void {
  if (!queryClientRef) return
  for (const key of [ASSETS_QUERY_KEY, LIBRARIES_QUERY_KEY, DIRS_QUERY_KEY, AUTHORS_QUERY_KEY]) {
    void queryClientRef.invalidateQueries({ queryKey: key })
  }
}

/**
 * 自动挂靠序列（同 App UploadAttacher）：先 PUT /assets/{id}/authors 单项
 * 整体替换（新上传资产零关联、传单项安全），后 PUT /authors/{authorId}/sources
 * mode=append（并入去重永不覆盖既有来源）——顺序固定（authors 可能触发服务端
 * 建作者块，sources append 依赖块存在）。返回 null = 全部挂上；否则为失败文案。
 * 走生成 SDK（挂靠无进度诉求，token/baseUrl 随全局 client）；任何失败不重试。
 */
async function attachSequence(assetId: string, attach: UploadAttach): Promise<string | null> {
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
}

/** 发送单条；resolve 于终态（done/failed/attach-failed/canceled），供队列顺序推进。
 *  目标只认入队快照（item.targetLibraryId/targetDir）——发送途中用户切换
 *  库/目录不影响已排队条目（§5 第 10 条修法；拖拽目录条目的 dir 亦为
 *  入队时刻由 relativePath 换算定格）。 */
function uploadOne(item: QueueEntry): Promise<void> {
  return new Promise<void>((resolve) => {
    const settle = (patch: Partial<UploadItem>): void => {
      xhrs.delete(item.id)
      canceledIds.delete(item.id)
      patchItem(item.id, patch)
      const final = entries.find((it) => it.id === item.id)
      // done/failed/attach-failed 通知（canceled 是用户主动行为，不再弹 toast打扰）
      if (final && final.status !== 'queued' && final.status !== 'uploading' && final.status !== 'canceled') {
        options.onItemSettled?.(final)
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

    // ≥16MB 走分片会话流（ADR-0028，阈值与状态机在 lib/upload-chunked，XHR 直连
    // 选型记档见其文件头）：dir 用入队快照（与直传 buildUploadQuery 同源——
    // item.targetDir），成功后走与直传完全相同的后处理（入库失效 + 挂靠序列），
    // 取消/失败按终态收敛（重试上限后不自动重新入队）。分支在直传 XHR 构造
    // 之前——分片条目不建直传句柄，xhrs 登记由 onActiveXhr 接管。
    if (useChunkedSession(item.sizeBytes)) {
      void runChunkedUpload({
        libraryId: item.targetLibraryId ?? '',
        fileName: item.name,
        dir: item.targetDir ?? '',
        sizeBytes: item.sizeBytes,
        file: item.file,
        isCanceled: () => canceledIds.has(item.id),
        onProgress: (percent) => patchItem(item.id, { percent }),
        onActiveXhr: (xhr) => {
          if (xhr === null) xhrs.delete(item.id)
          else xhrs.set(item.id, xhr)
        },
      }).then((outcome) => {
        if (outcome.kind === 'success') {
          invalidateUploaded()
          void attachThenSettle(outcome.body)
          return
        }
        if (outcome.kind === 'canceled') settle({ status: 'canceled' })
        else settle({ status: 'failed', errorText: outcome.message })
      })
      return
    }

    // query 组装收敛在 lib/upload-params.ts 纯函数（行为由测试锁定）
    const params = buildUploadQuery(
      item.targetLibraryId ?? '',
      item.targetDir ?? '',
      item.name,
    )
    const xhr = new XMLHttpRequest()
    xhrs.set(item.id, xhr)
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

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        invalidateUploaded()
        void attachThenSettle(xhr.responseText)
      } else {
        // 401/403 亦在此按单条失败落终态（既有语义：不整队 abort，服务端
        // message 透传；SDK 挂靠链路的 401 另经全局拦截器进 AuthGate）
        settle({ status: 'failed', errorText: serverErrorText(xhr) })
      }
    }
    xhr.onerror = () => settle({ status: 'failed', errorText: '网络错误，上传中断' })
    xhr.onabort = () => settle({ status: 'canceled' })
    xhr.send(item.file)
  })
}

/**
 * 串行泵：循环拾取权威副本里的排队条目逐条发送，直到没有排队态。
 * draining 标志保证全 store 生命周期最多一个泵在跑——后入队的批次不另起
 * 第二路（§5 第 11 条①「跨批并发」缺陷的修法），只会被在跑泵顺次拾取。
 */
async function pump(): Promise<void> {
  if (draining) return
  draining = true
  try {
    for (;;) {
      const next = entries.find((it) => it.status === 'queued')
      if (!next) break
      patchItem(next.id, { status: 'uploading' })
      await uploadOne(next)
    }
  } finally {
    draining = false
  }
}

function enqueue(inputs: readonly StagedFileInput[], batch: UploadBatchTarget): void {
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
    for (const it of blocked) options.onItemSettled?.(it)
    return
  }
  // 大小上限前置拦截（冻结口径：读 GET /config upload 项，超限不上网，中文提示）
  const limitMb = options.maxBytesMb
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
    if (it.status === 'failed') options.onItemSettled?.(it)
  }
  void pump()
}

/** 取消整队（用户显式「全部取消」——本 store 唯一的 abort 触发点；切路由/
 *  组件卸载不调用任何取消，见文件头记档）：在传的 abort（直传 onabort 标
 *  canceled；分片条目 abort 之外再立取消旗标，供请求间隙消费，会话由
 *  runChunkedUpload best-effort DELETE 放弃），排队的直接标 canceled。 */
function cancelAll(): void {
  for (const [id, xhr] of xhrs) {
    canceledIds.add(id)
    xhr.abort()
  }
  writeItems((prev) => prev.map((it) => (it.status === 'queued' ? { ...it, status: 'canceled' } : it)))
}

/** 清除终态行（在传/排队的不动） */
function clearFinished(): void {
  writeItems((prev) => prev.filter((it) => it.status === 'queued' || it.status === 'uploading'))
}

/** 订阅状态变化（useSyncExternalStore 用；返回解绑函数） */
function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** 渲染快照：权威副本的当前引用（写入口替换数组，引用相等 = 未变化） */
function getItems(): UploadItem[] {
  return entries
}

/** 订阅层 hook 实时同步运行选项（内联回调安全——store 只存最新引用） */
function setOptions(next: UploadQueueOptions): void {
  options = next
}

/** 订阅层 hook 挂载时注入 QueryClient（见 queryClientRef 注释：组件树外失效） */
function attachQueryClient(client: QueryClient): void {
  queryClientRef = client
}

/** 单例出口（hook 层唯一消费；测试经本对象驱动） */
export const uploadQueueStore = {
  subscribe,
  getItems,
  enqueue,
  cancelAll,
  clearFinished,
  setOptions,
  attachQueryClient,
} as const
