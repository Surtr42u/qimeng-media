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
 *
 * 刷新持久化（HANDOVER §5 待办#2，2026-10-02 落地）：未终态条目（排队/在传）
 * 同步持久化到 IndexedDB（lib/upload-queue-persist 纯核心 + persist-instance
 * 装配，最佳努力——存储失败不阻断传输）；页面刷新后 restorePersisted 把记录
 * 恢复为「需重新选择文件」（needs-file）态——File 句柄不跨页面存活是平台客观
 * 限制，恢复条目不伪造可续传假象，用户重选同名文件（resumeWithFile，同名同
 * 大小判定在纯函数 decideResume）后才恢复传输：分片条目按持久化会话 id 走既有
 * GET 探测→PATCH 续传（服务端 offset 唯一真相源），直传条目与无会话分片从头
 * 重传。终态（done/failed/attach-failed/canceled）/移除/清空完成时同步删除
 * 持久化记录，不留孤儿。
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
 *
 * 本文件超 500 行警戒线（web 单文件 ≤500 软约束）的理由：队列编排与传输
 * 状态机强内聚——入队/探测/续传决策/挂靠序列/取消/恢复全链共享同一份队列
 * 状态与 notify 单一写入口，按职责切开会造成跨文件状态双写与同步缝；可拆
 * 的部分已经拆出（持久化=lib/upload-queue-persist 纯核心+persist-instance
 * 装配，分片传输=lib/upload-chunked.ts），本文件只保留状态机本体与直传通道，
 * 行数即该本体的自然规模。
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
import { decideResume, toRestoredEntry, type PersistedUploadItem } from '@/lib/upload-queue-persist'
import { getUploadQueueStorage } from '@/lib/upload-queue-persist-instance'

/** MB → 字节换算（与服务端 config 口径一致：1MB = 1<<20，见 upload.go kvMax）。
 *  组件层「开始上传」超限前置拦截与 store 内兜底拦截共用本常量，禁止散抄 */
export const BYTES_PER_MB = 1 << 20

/** 上传单条状态：queued（排队）→ uploading（传输/服务端处理中）→ 终态四选一。
 *  attach-failed = 文件已入库但自动挂靠失败（不回滚上传、不重试，专项态提示）；
 *  needs-file = 刷新恢复条目（持久化记录还原，文件句柄已随页面失效，待用户
 *  重选同名文件——重选后转 queued 重新入泵，不满足即留在本态） */
export type UploadStatus =
  | 'queued'
  | 'uploading'
  | 'needs-file'
  | 'done'
  | 'failed'
  | 'canceled'
  | 'attach-failed'

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

/** 队列条目 id 前缀（生成与恢复解析共用单一来源——恢复侧按序号让位 uploadSeq
 *  防 id 冲突；不进协议） */
const UPLOAD_ID_PREFIX = 'upload-'

/** 队列内部条目 = 渲染契约 UploadItem + 传输所需的 File 句柄（file 不进组件层；
 *  刷新恢复条目无句柄 = null，重选文件后补上——needs-file 态永不被泵拾取）。
 *  目标快照直接落在 UploadItem.targetLibraryId/targetDir（渲染与发送同源，不双写）。
 *  chunkSessionId/chunkOffset/createdAt 为传输与持久化的内部字段（不在渲染契约）：
 *  会话 id 由分片通道 onSession 回写并持久化、断点偏移由 onOffset 回写并持久化 */
interface QueueEntry extends UploadItem {
  file: File | null
  /** 分片会话 id（null = 直传条目/会话未建立；恢复条目自持久化记录回填） */
  chunkSessionId: string | null
  /** 已传分片偏移（字节；服务端权威 offset 的最近快照，随片推进并持久化） */
  chunkOffset: number
  /** 入队/恢复时刻（epoch ms；持久化记录 createdAt 同源） */
  createdAt: number
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

// ---- 刷新持久化（未终态条目 → IndexedDB；最佳努力不阻断传输）----

/** 持久化写路径统一收口：存储失败静默（持久化是增强能力，传输主链路不受影响
 *  ——同打点账本降级口径）；fire-and-forget，不进传输 await 链 */
function persistPut(entry: QueueEntry): void {
  const record: PersistedUploadItem = {
    id: entry.id,
    name: entry.name,
    originalName: entry.originalName,
    sizeBytes: entry.sizeBytes,
    targetLibraryId: entry.targetLibraryId ?? '',
    targetDir: entry.targetDir ?? '',
    attach: entry.attach,
    sessionId: entry.chunkSessionId,
    chunkOffset: entry.chunkOffset,
    createdAt: entry.createdAt,
    updatedAt: Date.now(),
  }
  void getUploadQueueStorage()
    .then((storage) => storage.put(record))
    .catch(() => {
      // 存储不可用（隐私模式等）：放弃本条持久化，刷新后该条按现状丢失
    })
}

/** 持久化删路径（终态/移除/清空完成时调用，不留孤儿记录）；同样静默兜底 */
function persistDelete(id: string): void {
  void getUploadQueueStorage()
    .then((storage) => storage.delete(id))
    .catch(() => {
      // 存储不可用：无可删（下次 restore 时记录本就不存在）
    })
}

/** 权威副本变更的唯一写入口（替换数组引用——useSyncExternalStore 靠引用
 *  变化感知快照更新，原地 mutate 会漏通知） */
function writeItems(updater: (prev: QueueEntry[]) => QueueEntry[]): void {
  entries = updater(entries)
  notify()
}

/** 条目补丁（含内部字段 chunkSessionId/chunkOffset；渲染字段变化经 notify 同步） */
function patchEntry(id: string, patch: Partial<QueueEntry>): void {
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
      // 终态即清理持久化记录（不留孤儿；needs-file 不进本函数——恢复条目由
      // resumeWithFile 转 queued 后才入泵）
      persistDelete(item.id)
      patchEntry(item.id, patch)
      const final = entries.find((it) => it.id === item.id)
      // done/failed/attach-failed 通知（canceled 是用户主动行为，不再弹 toast打扰）
      if (final && final.status !== 'queued' && final.status !== 'uploading' && final.status !== 'canceled') {
        options.onItemSettled?.(final)
      }
      resolve()
    }

    // 防御守卫（类型上不可达：泵只拾取带句柄的 queued 条目）——缺句柄按失败
    // 落终态而非崩溃，泵继续拾取后续条目
    const file = item.file
    if (file === null) {
      settle({ status: 'failed', errorText: '内部状态异常：队列条目缺少文件句柄，已终止' })
      return
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
    // 刷新恢复条目带 chunkSessionId 传入续传（探测 404 时通道内自动重建新会话）；
    // onSession/onOffset 把会话 id 与权威断点回写进条目并持久化（刷新再恢复的依据）。
    if (useChunkedSession(item.sizeBytes)) {
      void runChunkedUpload({
        libraryId: item.targetLibraryId ?? '',
        fileName: item.name,
        dir: item.targetDir ?? '',
        sizeBytes: item.sizeBytes,
        file,
        sessionId: item.chunkSessionId ?? undefined,
        isCanceled: () => canceledIds.has(item.id),
        onProgress: (percent) => patchEntry(item.id, { percent }),
        onSession: (sessionId) => {
          patchEntry(item.id, { chunkSessionId: sessionId })
          const current = entries.find((it) => it.id === item.id)
          if (current) persistPut(current)
        },
        onOffset: (offset) => {
          patchEntry(item.id, { chunkOffset: offset })
          const current = entries.find((it) => it.id === item.id)
          if (current) persistPut(current)
        },
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
      patchEntry(item.id, { percent: Math.round((e.loaded / e.total) * 100) })
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
      patchEntry(next.id, { status: 'uploading' })
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
        id: `${UPLOAD_ID_PREFIX}${++uploadSeq}`,
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
        chunkSessionId: null,
        chunkOffset: 0,
        createdAt: Date.now(),
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
    else persistPut(it) // 通过校验的条目（queued）入持久化——刷新可恢复
  }
  void pump()
}

/** 取消整队（用户显式「全部取消」——本 store 唯一的 abort 触发点；切路由/
 *  组件卸载不调用任何取消，见文件头记档）：在传的 abort（直传 onabort 标
 *  canceled；分片条目 abort 之外再立取消旗标，供请求间隙消费，会话由
 *  runChunkedUpload best-effort DELETE 放弃），排队的直接标 canceled。
 *  canceled 是终态：排队条目的持久化记录就地删除（在传条目经 settle 删），
 *  needs-file 恢复条目无传输可取消、原样保留（由用户重选或逐条移除）。 */
function cancelAll(): void {
  for (const [id, xhr] of xhrs) {
    canceledIds.add(id)
    xhr.abort()
  }
  const queuedIds = entries.filter((it) => it.status === 'queued').map((it) => it.id)
  writeItems((prev) => prev.map((it) => (it.status === 'queued' ? { ...it, status: 'canceled' } : it)))
  for (const id of queuedIds) persistDelete(id)
}

/** 清除终态行（在传/排队/待重选的不动——needs-file 不是完成态，清除已完成
 *  不替用户丢弃待恢复条目），并同步删除其持久化记录（终态记录已在 settle
 *  删除，此处兜底防存储短暂不可用期的漏删） */
function clearFinished(): void {
  const isTransient = (status: UploadStatus): boolean =>
    status === 'queued' || status === 'uploading' || status === 'needs-file'
  const removedIds = entries.filter((it) => !isTransient(it.status)).map((it) => it.id)
  writeItems((prev) => prev.filter((it) => isTransient(it.status)))
  for (const id of removedIds) persistDelete(id)
}

/** 移除单条（队列页对 needs-file 恢复条目的「移除」；通用能力，任意条目可删）
 *  并同步删除持久化记录 */
function removeItem(id: string): void {
  writeItems((prev) => prev.filter((it) => it.id !== id))
  persistDelete(id)
}

/** 单飞闸：并发 restore 合并同一轮（恢复幂等：已在队列的 id 跳过，可安全重复触发） */
let restoring: Promise<void> | null = null

/**
 * 刷新恢复：读取持久化记录还原为 needs-file 条目（文件句柄已随页面失效——
 * 平台客观限制，不伪造续传假象，等用户重选）。幂等可重触发：单飞闸合并并发，
 * 已在队列的记录 id 跳过；新入队 id 序号让位恢复 id（防 `${PREFIX}${n}` 冲突）。
 * 存储不可用 = 无恢复（按现状丢队列，与持久化写入同款降级口径）。
 */
function restorePersisted(): Promise<void> {
  if (restoring) return restoring
  restoring = (async () => {
    let records: PersistedUploadItem[]
    try {
      records = await (await getUploadQueueStorage()).getAll()
    } catch {
      return
    }
    const existing = new Set(entries.map((it) => it.id))
    const fresh: QueueEntry[] = []
    for (const record of records) {
      if (existing.has(record.id)) continue
      const restored = toRestoredEntry(record)
      fresh.push({
        id: restored.id,
        name: restored.name,
        originalName: restored.originalName,
        sizeBytes: restored.sizeBytes,
        status: 'needs-file',
        percent: restored.percent,
        targetLibraryId: restored.targetLibraryId,
        targetDir: restored.targetDir,
        attach: restored.attach,
        errorText: undefined,
        file: null,
        chunkSessionId: restored.sessionId,
        chunkOffset: restored.chunkOffset,
        createdAt: restored.createdAt,
      })
      const seq = Number.parseInt(record.id.slice(UPLOAD_ID_PREFIX.length), 10)
      if (!Number.isNaN(seq) && seq >= uploadSeq) uploadSeq = seq + 1
    }
    if (fresh.length === 0) return
    writeItems((prev) => [...prev, ...fresh])
  })()
    .catch(() => {
      // 恢复尽力而为：还原失败不阻塞队列页其余功能
    })
    .finally(() => {
      restoring = null
    })
  return restoring
}

/** 恢复条目重选文件（队列页「重选文件」入口）：同名同大小判定在纯函数
 *  decideResume——mismatch 留在 needs-file 并给文案；命中则补句柄转 queued
 *  重新入泵（有持久化会话 id 的分片条目按既有探测→PATCH 续传，其余从头）。 */
function resumeWithFile(id: string, file: File): void {
  const entry = entries.find((it) => it.id === id)
  if (entry === undefined || entry.status !== 'needs-file') return
  const decision = decideResume(
    {
      name: entry.name,
      originalName: entry.originalName,
      sizeBytes: entry.sizeBytes,
      sessionId: entry.chunkSessionId,
    },
    { name: file.name, size: file.size },
  )
  if (decision.kind === 'mismatch') {
    patchEntry(id, { errorText: '所选文件与原条目不一致（文件名或大小不符），未恢复上传' })
    return
  }
  const resumed = decision.kind === 'resume-session'
  writeItems((prev) =>
    prev.map((it) =>
      it.id === id
        ? {
            ...it,
            file,
            status: 'queued',
            errorText: undefined,
            chunkSessionId: resumed ? decision.sessionId : null,
            // 从头重传（直传/无会话分片）进度与断点归零；会话续传保留展示进度
            chunkOffset: resumed ? it.chunkOffset : 0,
            percent: resumed ? it.percent : 0,
          }
        : it,
    ),
  )
  void pump()
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
  removeItem,
  restorePersisted,
  resumeWithFile,
  setOptions,
  attachQueryClient,
} as const
