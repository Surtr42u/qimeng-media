/**
 * API 客户端单例配置（数据层底座）。
 *
 * 职责（分层纪律：components 禁止 import 本文件，hooks 是唯一消费方）：
 * 1. 包装 @hey-api 生成 client（api/generated/，禁手改），设置鉴权来源与拦截
 * 2. auth 每次请求前从 localStorage 读取 → 生成 client 自动加 `Bearer ` 前缀（core/auth.gen.ts）
 * 3. 401/403 统一触发 onAuthFailed（AuthGate 截获后清 token 回门禁）
 * 4. 提供 SDK 结果解包辅助 unwrapSdkResult 与裸 fetch 用的 getAuthHeaders
 */

import { client } from '@/api/generated/client.gen'
import { TOKEN_STORAGE_KEY } from './constants'

/**
 * 认证失败监听器集合：AuthGate / 长连接订阅方注册。
 * 用事件而非回调参数，是为了让"任何一处请求 401"都能把用户带回门禁，
 * 而不需要每个调用点各自判断（AI_README「禁止隐式调度耦合」：这是显式事件）。
 */
type AuthFailedListener = () => void
const authFailedListeners = new Set<AuthFailedListener>()

/** 注册鉴权失败监听，返回解绑函数（组件卸载时调用）。 */
export function onAuthFailed(listener: AuthFailedListener): () => void {
  authFailedListeners.add(listener)
  return () => authFailedListeners.delete(listener)
}

/** 触发鉴权失败事件（仅内部与 sse.ts 使用：SSE 是裸 fetch，不走 client 拦截器）。 */
export function emitAuthFailed(): void {
  for (const listener of authFailedListeners) listener()
}

/**
 * token 模块级 store（useSyncExternalStore 订阅）。
 * 为什么必须提升为模块状态：多个组件（AuthGate/登录面板/页面）各自 useState
 * 会持有不同副本，任何一处清 token 其他组件不感知；单一 store + 订阅保证全局同步。
 */
let currentToken: string | null = localStorage.getItem(TOKEN_STORAGE_KEY) || null
const tokenListeners = new Set<() => void>()

function notifyTokenChanged(): void {
  for (const listener of tokenListeners) listener()
}

/** 当前 token（订阅可见的一致性视图） */
export function getToken(): string | null {
  return currentToken
}

/** 订阅 token 变化（useSyncExternalStore 用） */
export function subscribeToken(listener: () => void): () => void {
  tokenListeners.add(listener)
  return () => {
    tokenListeners.delete(listener)
  }
}

/** 写入 token（原始 token，不带 Bearer 前缀——与 M1 验收页同格式）。 */
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_STORAGE_KEY, token)
  currentToken = token
  notifyTokenChanged()
}

/** 清除 token（取消鉴权：门禁/退出时调）。 */
export function clearToken(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY)
  currentToken = null
  notifyTokenChanged()
}

/** 组装请求头（供裸 fetch 使用：sse.ts / use-sources.ts / use-upload.ts 等不走 SDK 的场景）。 */
export function getAuthHeaders(): Record<string, string> {
  // M1 验收页约定 raw token 存 localStorage，请求时加 Bearer 前缀（两处共同来源见 constants.ts）
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  return token ? { Authorization: `Bearer ${token}` } : {}
}

// 全局拦截：401/403 且非 auth 端点 → 广播鉴权失败。
// 豁免 /api/v1/auth/*：verify 返回 401 是"token 无效"的正常业务信号，
// LoginGate 需自己处理；若也触发全局事件，会在门禁表单上造成循环重定向。
client.interceptors.response.use((response) => {
  const { status, url } = response
  if ((status === 401 || status === 403) && !url.includes('/api/v1/auth/')) {
    emitAuthFailed()
  }
  return response
})

// 同源 baseUrl（'' = 相对路径）：dev 走 Vite proxy，生产由服务端托管 dist（见 vite.config.ts）
client.setConfig({
  baseUrl: '',
  // 每次请求前取当前 token：setToken/clearToken 会同步更新模块状态，
  // 避免闭包捕获过期值（跨标签页写 localStorage 的极端情形不覆盖——保持页面内一致优先）
  auth: () => currentToken ?? undefined,
})

/**
 * SDK 结果解包：generated client（throwOnError=false）返回 Promise<{ data, error } 判别联合>。
 * TError 多为 unknown，TS 无法靠 error 字段收窄 data，故用断言——
 * 这是生成物的已知形状（成功时必有 data），非运行时风险。
 * 设计为 async（吃 Promise）：queryFn/mutationFn 直接透传 SDK 调用即可，无需调用点 await 两次。
 */
export async function unwrapSdkResult<T>(
  res: Promise<{ data?: T; error?: unknown; response?: Response }>,
): Promise<T> {
  const result = await res
  if (result.error !== undefined) {
    // hey-api 把 HTTP 状态码放在原生 Response 上，错误体只有 {code,message}——
    // 把 status 并进错误对象再抛，调用方才能按状态码分流（LoginGate 409=
    // 已初始化切登录模式；实测缺失时 409 被误判成"初始化失败"）。
    const status = result.response?.status
    throw (status !== undefined && result.error && typeof result.error === 'object'
      ? Object.assign(result.error as object, { status })
      : result.error)
  }
  return result.data as T
}
