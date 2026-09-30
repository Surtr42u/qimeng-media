/**
 * 断点续传分片通道（ADR-0028 Web 接入批）：≥16MB 文件走 POST /api/v1/uploads
 * 会话流——create → 每轮先 GET 探测服务端权威 offset → File.slice 按 8MB PATCH
 * 追加 → offset 达 size 后 complete。语义照搬 Android 参考实现
 *（core/data/upload/ChunkedUploadSession + UploadRouting，双侧注释互指双同步）：
 * 断点以服务端为唯一真相源（本地不存 offset，每次重试先探测再续传，天然幂等）；
 * PATCH 409 响应体即权威 UploadSession，立即重同步继续（不算失败）；会话 404
 *（过期被清扫/服务端重启）重建会话从 0（单条上限 2 次防死循环）；网络/5xx/超时
 * 指数退避自动重试（同条上限 3 次，重试上限后终态失败、不自动重新入队）；
 * "offset 连续 8 轮未推进"防御中止（服务端异常实现兜底，正常串行追加不可达）。
 *
 * 传输层选型记档（为何不走生成 SDK——参照 Android @UploadClient OkHttp 直连前例，
 * Android OkHttpUploadSessionClient KDoc 同源论证，本端三条理由两条成立）：
 * ① 生成 client（hey-api）请求选项无 abort/signal 支持（生成物 grep 零命中），
 *    「全部取消」要求 abort 在途分片请求，此步生成客户端做不到；
 * ② 全局 client 的响应拦截器（lib/api-client.ts）把一切非 auth 端点的 401/403
 *    广播为鉴权失效（AuthGate 清 token 踢回门禁），而本通道 create 的 403
 *    UPLOAD_DISABLED（upload.autoAccept=false）是正常业务响应——直传通道既有
 *    口径是条目级失败透传 message、不广播，走全局 SDK 会引入误踢登录的回归；
 * ③（非阻断记档）fetch 无上传字节级进度，分片进度按片粒度推进（每片一跳）
 *    本就不依赖它。
 * 故整通道五操作 XHR 直连同款路径常量（lib/constants UPLOADS_SESSION_PATH），
 * 鉴权与直传共用 api-client getAuthHeaders。路径/参数与 api/openapi.yaml
 * /api/v1/uploads 三路径模板双同步：协议侧改动须同步此处，反之亦然。
 */

import { getAuthHeaders } from '@/lib/api-client'
import { UPLOADS_SESSION_PATH } from '@/lib/constants'

/**
 * 分片会话通道的文件大小阈值（字节）：≥ 此值走 POST /api/v1/uploads 会话流，
 * < 此值走既有整文件直传（store 内现状 XHR 一字未动）。16MB 取舍同 Android
 * UploadRouting.CHUNKED_THRESHOLD_BYTES（双侧双同步）：LAN 小文件单发直传更快
 *（一次往返即完成，无 create/probe/PATCH/complete 四步握手开销）；会话流的
 * 价值在弱网大文件按片续传。协议侧改动须同步此处，反之亦然。
 */
export const CHUNKED_THRESHOLD_BYTES = 16 * 1024 * 1024

/**
 * 单个分片大小（字节）：PATCH /uploads/{id} 每次追加的原始字节数。8MB = 服务端
 * 单片上限 32MB 的 1/4（客户端常规路径永不触发 413），请求数与进度粒度（每片
 * 一跳）的折中——同 Android UploadRouting.CHUNK_BYTES（双侧双同步）。
 */
export const CHUNK_BYTES = 8 * 1024 * 1024

/** 同一条目内网络/5xx/超时的自动重试上限（不含首次尝试；超限终态失败） */
export const MAX_TRANSIENT_RETRIES = 3

/** 重试退避基础延迟（ms）：指数退避 2^(n-1) 递增（1s/2s/4s） */
export const RETRY_BACKOFF_BASE_MS = 1000

/** 退避上限（ms）：防指数失控（当前 3 次重试天然 ≤4s，纯兜底护栏） */
export const RETRY_BACKOFF_MAX_MS = 8000

/** 单条目内会话重建（404 后重新 create 从 0 续传）上限——防 create→probe 404
 *  的服务端异常死循环（Android MAX_SESSION_REBUILDS 同值，双侧双同步） */
export const MAX_SESSION_REBUILDS = 2

/** 连续未推进轮次上限：服务端 offset 多轮不前即转重试，避免空转
 *（Android MAX_NO_ADVANCE_ROUNDS 同值，双侧双同步） */
export const MAX_NO_ADVANCE_ROUNDS = 8

/**
 * 单请求超时（ms）：浏览器 XHR timeout 计整个请求（无 Android 读/写分离超时），
 * 弱网 8MB 分片取 2 分钟（Android @UploadClient 60s 的放宽版——移动上行
 * ~0.6Mbps 亦可在限内传完一片，超限按可重试网络错误处理，重试从探测续传）。
 */
export const CHUNK_REQUEST_TIMEOUT_MS = 120000

/** 是否走分片会话通道：纯 size 阈值分流（Android UploadRouting 同款纯函数，
 *  dir 已入分片协议——目标子目录同享续传，分流与目标无关） */
export function useChunkedSession(sizeBytes: number): boolean {
  return sizeBytes >= CHUNKED_THRESHOLD_BYTES
}

// ---- 单步结果分类（Android SessionCall 三态在 Web 的展开形态）----

/** HTTP 响应（任何状态码都到这，分类在调用方） */
interface XhrReply {
  status: number
  body: string
}

type StepResult<T> =
  | { tag: 'ok'; value: T }
  /** PATCH 409 专属：响应体即权威 UploadSession（authorityOffset 解析失败回 null，
   *  调用方回退重新探测——Android authorityOffset ?: UNPROBED 同款防御） */
  | { tag: 'conflict'; authorityOffset: number | null }
  | { tag: 'notFound' }
  /** 4xx 校验类（400 累计超 size / 403 上传关闭 / 413 单片超限等）：终态失败 */
  | { tag: 'clientError'; message: string }
  /** 网络/5xx/超时/响应体不可解析：可重试 */
  | { tag: 'transient'; message: string }
  | { tag: 'canceled' }

/** create/probe/complete 的结果形态：409 只在 PATCH 出现，排除后调用方分支可穷尽收窄 */
type SimpleStep<T> = Exclude<StepResult<T>, { tag: 'conflict'; authorityOffset: number | null }>

/** runChunkedUpload 终局：success.body = complete 201 响应体原文（与直传 201
 *  同构的资产详情 JSON，store 侧复用直传完全相同的成功后处理）；canceled =
 *  用户显式取消；failed.message = 终态失败文案（4xx 服务端 message / 重试
 *  上限文案；重试上限后不自动重新入队，交用户手动重传） */
export type ChunkedOutcome =
  | { kind: 'success'; body: string }
  | { kind: 'canceled' }
  | { kind: 'failed'; message: string }

/** 4xx/5xx 响应体文案：服务端 {code,message} 的 message 是唯一口径。与 store
 *  内直传的 serverErrorText 同语义——不共享是因为依赖方向已定型为 store→本
 *  模块单向（反向 import 成环），刻意平行，两侧同步维护 */
function errorTextOf(status: number, body: string): string {
  try {
    const parsed = JSON.parse(body) as { message?: unknown }
    if (typeof parsed.message === 'string' && parsed.message !== '') return parsed.message
  } catch {
    // 非 JSON（代理页/空体）：落状态码兜底
  }
  return `上传失败（HTTP ${status}）`
}

/** UploadSession 响应体关心字段（create 用；POST/GET 2xx 与 PATCH 409 响应体
 *  同构，字段与 openapi UploadSession schema 双同步）。解析失败回 null——
 *  防御性收敛为可重试/重探测，不按终态失败处理 */
function parseSessionId(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { id?: unknown }
    return typeof parsed.id === 'string' && parsed.id !== '' ? parsed.id : null
  } catch {
    return null
  }
}

/** 权威 offset 提取（probe 2xx / PATCH 2xx 与 409 共用；合法性域 ≥0 且有限） */
function parseOffset(body: string): number | null {
  try {
    const parsed = JSON.parse(body) as { offset?: unknown }
    if (typeof parsed.offset === 'number' && Number.isFinite(parsed.offset) && parsed.offset >= 0) {
      return parsed.offset
    }
    return null
  } catch {
    return null
  }
}

/** 会话路径拼装（path 段 encodeURIComponent；与 UPLOADS_SESSION_PATH 双同步） */
function sessionUrl(sessionId: string): string {
  return `${UPLOADS_SESSION_PATH}/${encodeURIComponent(sessionId)}`
}

/** HTTP 非 2xx 统一分类（404/5xx/4xx；1xx/3xx 在 XHR 同源请求下不可达，防御归可重试） */
function classifyReply(reply: XhrReply): SimpleStep<never> {
  if (reply.status === 404) return { tag: 'notFound' }
  if (reply.status >= 500) return { tag: 'transient', message: `服务端错误（HTTP ${reply.status}）` }
  if (reply.status >= 400) return { tag: 'clientError', message: errorTextOf(reply.status, reply.body) }
  return { tag: 'transient', message: `上传响应异常（HTTP ${reply.status}）` }
}

/** sendXhr 的 reject 值分类回 StepResult（canceled 透传，其余归网络可重试） */
function transportStep(err: unknown): SimpleStep<never> {
  if (err !== null && typeof err === 'object' && (err as { kind?: unknown }).kind === 'canceled') {
    return { tag: 'canceled' }
  }
  return { tag: 'transient', message: '网络错误，上传中断' }
}

/**
 * XHR 单发：HTTP 任何状态码 resolve（含 4xx/5xx——会话通道要读 409 权威体与
 * 4xx 文案），网络错误/超时 reject {kind:'network'}，用户 abort reject
 * {kind:'canceled'}。onActive 供 store 登记在途句柄（cancelAll abort 用；
 * 收尾传 null 摘除）。
 */
function sendXhr(opts: {
  method: string
  url: string
  body?: XMLHttpRequestBodyInit
  contentType?: string
  onActive: (xhr: XMLHttpRequest | null) => void
}): Promise<XhrReply> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    opts.onActive(xhr)
    xhr.open(opts.method, opts.url)
    for (const [key, value] of Object.entries(getAuthHeaders())) {
      xhr.setRequestHeader(key, value)
    }
    if (opts.contentType !== undefined) xhr.setRequestHeader('Content-Type', opts.contentType)
    xhr.timeout = CHUNK_REQUEST_TIMEOUT_MS
    const done = (settle: () => void): void => {
      opts.onActive(null)
      settle()
    }
    xhr.onload = () => done(() => resolve({ status: xhr.status, body: xhr.responseText }))
    xhr.onerror = () => done(() => reject({ kind: 'network' }))
    xhr.ontimeout = () => done(() => reject({ kind: 'network' }))
    xhr.onabort = () => done(() => reject({ kind: 'canceled' }))
    xhr.send(opts.body)
  })
}

// ---- 五操作（与 openapi /api/v1/uploads 三路径模板一一对应）----

/** create：body {libraryId, fileName, dir, size}。contentType 不发——服务端不信任
 *  声明（魔数嗅探为准），Android 同款省去 MIME 推断链；dir 恒发送（空串=库根） */
async function createSession(
  args: ChunkedUploadArgs,
): Promise<SimpleStep<string>> {
  try {
    const reply = await sendXhr({
      method: 'POST',
      url: UPLOADS_SESSION_PATH,
      body: JSON.stringify({
        libraryId: args.libraryId,
        fileName: args.fileName,
        dir: args.dir,
        size: args.sizeBytes,
      }),
      contentType: 'application/json',
      onActive: args.onActiveXhr,
    })
    if (reply.status >= 200 && reply.status < 300) {
      const id = parseSessionId(reply.body)
      return id !== null ? { tag: 'ok', value: id } : { tag: 'transient', message: '创建上传会话响应体不可解析' }
    }
    return classifyReply(reply)
  } catch (err) {
    return transportStep(err)
  }
}

/** probe：GET 探测服务端权威 offset（断点唯一真相源；404=会话已失效） */
async function probeSession(sessionId: string, onActive: ChunkedUploadArgs['onActiveXhr']): Promise<SimpleStep<number>> {
  try {
    const reply = await sendXhr({ method: 'GET', url: sessionUrl(sessionId), onActive })
    if (reply.status >= 200 && reply.status < 300) {
      const offset = parseOffset(reply.body)
      return offset !== null ? { tag: 'ok', value: offset } : { tag: 'transient', message: '断点探测响应体不可解析' }
    }
    return classifyReply(reply)
  } catch (err) {
    return transportStep(err)
  }
}

/** patch：octet-stream 追加一片；409 响应体是权威 UploadSession（原样带回） */
async function patchChunk(
  sessionId: string,
  offset: number,
  blob: Blob,
  onActive: ChunkedUploadArgs['onActiveXhr'],
): Promise<StepResult<number>> {
  try {
    const reply = await sendXhr({
      method: 'PATCH',
      url: `${sessionUrl(sessionId)}?offset=${offset}`,
      body: blob,
      contentType: 'application/octet-stream',
      onActive,
    })
    if (reply.status === 409) return { tag: 'conflict', authorityOffset: parseOffset(reply.body) }
    if (reply.status >= 200 && reply.status < 300) {
      const next = parseOffset(reply.body)
      return next !== null ? { tag: 'ok', value: next } : { tag: 'transient', message: '分片响应体不可解析' }
    }
    return classifyReply(reply)
  } catch (err) {
    return transportStep(err)
  }
}

/** complete：201 响应体 = 与直传同构的资产详情 JSON（原样上交 store 走直传同款后处理） */
async function completeSession(sessionId: string, onActive: ChunkedUploadArgs['onActiveXhr']): Promise<SimpleStep<string>> {
  try {
    const reply = await sendXhr({ method: 'POST', url: `${sessionUrl(sessionId)}/complete`, onActive })
    if (reply.status >= 200 && reply.status < 300) return { tag: 'ok', value: reply.body }
    return classifyReply(reply)
  } catch (err) {
    return transportStep(err)
  }
}

/** 放弃会话（用户取消时 best-effort DELETE）：发后即忘，失败不阻断取消终态
 *（临时文件由服务端 24h 过期清扫兜底）；不登记 onActive——cancelAll 已跑完 */
function abandonSession(sessionId: string): void {
  const xhr = new XMLHttpRequest()
  xhr.open('DELETE', sessionUrl(sessionId))
  for (const [key, value] of Object.entries(getAuthHeaders())) {
    xhr.setRequestHeader(key, value)
  }
  xhr.send()
}

// ---- 会话流编排（ChunkedUploadSession.run 的 Web 形态：重试内联为循环）----

/** runChunkedUpload 入参（store 的单条分片上传；file 只在本模块内 slice 消费） */
export interface ChunkedUploadArgs {
  libraryId: string
  fileName: string
  /** 目标子目录（库内相对路径；'' = 库根——与直传 dir 参数逐字同语义，入队快照） */
  dir: string
  sizeBytes: number
  file: File
  /** 取消旗标（store 的 cancelAll 立旗；请求间隙由本函数循环顶消费） */
  isCanceled: () => boolean
  /** 分片粒度进度（每片成功推进一次，参数 = 百分比 0–100，映射既有 item 进度字段） */
  onProgress: (percent: number) => void
  /** 在途 XHR 登记钩子（store 纳入 cancelAll abort 范围；null = 摘除） */
  onActiveXhr: (xhr: XMLHttpRequest | null) => void
}

/** 尚未探测哨兵（offset 合法域 ≥0，Android UNPROBED 同款） */
const UNPROBED = -1

/** 可中断 sleep（退避等待；到点后由循环顶 isCanceled 统一裁决取消） */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 单条分片上传全程：create（一次）→ 循环{探测→按片 PATCH→complete}，内含
 * 409 重同步 / 404 重建（上限 2）/ 网络·5xx·超时指数退避重试（上限 3，每次
 * 重试从 GET 探测续传）。终局只落 success/canceled/failed 三态，store 据此
 * 走与直传完全相同的后处理或终态。
 */
export async function runChunkedUpload(args: ChunkedUploadArgs): Promise<ChunkedOutcome> {
  let sessionId: string | null = null
  let rebuilds = 0
  let transientRetries = 0
  let offset = UNPROBED
  let noAdvanceRounds = 0

  /** 可重试错误统一走此门：上限内指数退避后继续（下轮循环顶从 GET 探测续传）；
   *  超限终态失败（不自动重新入队）。返回 null = 已安排重试、继续循环 */
  const transientRetry = async (message: string): Promise<ChunkedOutcome | null> => {
    if (transientRetries >= MAX_TRANSIENT_RETRIES) {
      return { kind: 'failed', message: `${message}；自动重试 ${MAX_TRANSIENT_RETRIES} 次仍失败，已停止` }
    }
    transientRetries += 1
    await sleep(Math.min(RETRY_BACKOFF_BASE_MS * 2 ** (transientRetries - 1), RETRY_BACKOFF_MAX_MS))
    offset = UNPROBED
    return null
  }

  /** 会话失效（404 = 过期被清扫/服务端重启）后的重建：置空会话让下轮循环顶
   *  重新 create 从 0 续传；单条上限 MAX_SESSION_REBUILDS 次，超限终态失败 */
  const rebuild = (reason: string): ChunkedOutcome | null => {
    if (rebuilds >= MAX_SESSION_REBUILDS) {
      return { kind: 'failed', message: `上传会话反复失效（${reason}），已停止` }
    }
    rebuilds += 1
    noAdvanceRounds = 0
    sessionId = null
    offset = UNPROBED
    return null
  }

  /** 取消终局统一出口：放弃会话（best-effort DELETE，失败不阻断）——用户
   *  显式取消无论发生在在途请求（abort）还是请求间隙（旗标）都走到这 */
  const cancelOut = (): ChunkedOutcome => {
    if (sessionId !== null) abandonSession(sessionId)
    return { kind: 'canceled' }
  }

  for (;;) {
    if (args.isCanceled()) return cancelOut()

    // 会话建立（首次与重建共用此口）：创建即做服务端可前置校验（白名单/超限/
    // 上传关闭 403），4xx 直接终态失败带服务端文案；可重试错误走退避门
    if (sessionId === null) {
      const created = await createSession(args)
      if (created.tag === 'ok') {
        sessionId = created.value
      } else if (created.tag === 'transient') {
        const stop = await transientRetry(`创建上传会话失败：${created.message}`)
        if (stop !== null) return stop
      } else if (created.tag === 'canceled') {
        return cancelOut()
      } else if (created.tag === 'notFound') {
        // create 语义上不产生 404（classifyReply 的形态兜底）：按终态失败处理
        return { kind: 'failed', message: '创建上传会话失败（服务端响应异常）' }
      } else {
        return { kind: 'failed', message: created.message }
      }
      continue
    }

    if (offset === UNPROBED) {
      // 每轮（含首次与每次重试）先探测：服务端是断点唯一真相源
      const probed = await probeSession(sessionId, args.onActiveXhr)
      if (probed.tag === 'ok') {
        offset = probed.value
      } else if (probed.tag === 'notFound') {
        const stop = rebuild('断点探测 404（会话已被清扫/服务端重启）')
        if (stop !== null) return stop
      } else if (probed.tag === 'canceled') {
        return cancelOut()
      } else if (probed.tag === 'transient') {
        const stop = await transientRetry(`断点探测失败：${probed.message}`)
        if (stop !== null) return stop
      } else {
        return { kind: 'failed', message: probed.message }
      }
      continue
    }

    if (offset >= args.sizeBytes) {
      // complete 与直传逐字同语义（同名自动改名、永不 409），响应即同构资产详情
      const done = await completeSession(sessionId, args.onActiveXhr)
      if (done.tag === 'ok') {
        return { kind: 'success', body: done.value }
      }
      if (done.tag === 'notFound') {
        const stop = rebuild('完结时 404（会话已被清扫）')
        if (stop !== null) return stop
      } else if (done.tag === 'canceled') {
        return cancelOut()
      } else if (done.tag === 'transient') {
        const stop = await transientRetry(`完结上传会话失败：${done.message}`)
        if (stop !== null) return stop
      } else {
        return { kind: 'failed', message: done.message }
      }
      continue
    }

    // 按片追加（File.slice 末片取余）；用响应权威 offset 推进，进度每片一跳
    const chunkEnd = Math.min(offset + CHUNK_BYTES, args.sizeBytes)
    const patched = await patchChunk(sessionId, offset, args.file.slice(offset, chunkEnd), args.onActiveXhr)
    if (patched.tag === 'ok') {
      if (patched.value <= offset) {
        // 防御：服务端 offset 未推进（异常实现）——连续多轮即中止转重试，
        // 避免空转（真实服务端串行追加语义下不可达）
        noAdvanceRounds += 1
        if (noAdvanceRounds >= MAX_NO_ADVANCE_ROUNDS) {
          const stop = await transientRetry('服务端 offset 多轮未推进')
          if (stop !== null) return stop
        }
      } else {
        noAdvanceRounds = 0
        offset = patched.value
        args.onProgress(Math.round((offset / args.sizeBytes) * 100))
      }
    } else if (patched.tag === 'conflict') {
      // offset 过期（权威漂移）：409 响应体即权威 UploadSession，立即重同步
      // 继续发送，不算失败；无权威体的防御分支回退重新探测
      offset = patched.authorityOffset ?? UNPROBED
    } else if (patched.tag === 'notFound') {
      const stop = rebuild('分片时 404（会话已被清扫）')
      if (stop !== null) return stop
    } else if (patched.tag === 'canceled') {
      return cancelOut()
    } else if (patched.tag === 'transient') {
      const stop = await transientRetry(`分片上传失败：${patched.message}`)
      if (stop !== null) return stop
    } else {
      // 4xx 校验类（400 累计超 size / 413 单片超限等）：终态失败带服务端文案
      return { kind: 'failed', message: patched.message }
    }
  }
}
