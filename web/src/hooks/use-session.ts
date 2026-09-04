/**
 * 会话与认证 hooks（AuthGate / 登录面板的消费入口）。
 */

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useMutation } from '@tanstack/react-query'
import { postApiV1AuthDevLogin, postApiV1AuthLogin, postApiV1AuthSetup, postApiV1AuthVerify } from '@/api/generated'
import {
  clearToken as clearStoredToken,
  getToken,
  onAuthFailed,
  setToken as storeToken,
  subscribeToken,
  unwrapSdkResult,
} from '@/lib/api-client'
import { SESSION_ID_STORAGE_KEY } from '@/lib/constants'

/**
 * 每标签页会话 ID：sessionStorage 存放（标签页关闭即弃）。
 * 为什么不用 localStorage：服务端按 assetId+kind+sessionId+当日 做会话级去重
 * （DOMAIN_RULES §5）——每标签页独立会话，同一天内重复打点不重复计数，
 * 但重新打开标签页 = 新会话，避免"今天看过"的计数被旧标签页永久占用。
 */
export function ensureSessionId(): string {
  const existing = sessionStorage.getItem(SESSION_ID_STORAGE_KEY)
  if (existing) return existing
  const id = crypto.randomUUID()
  sessionStorage.setItem(SESSION_ID_STORAGE_KEY, id)
  return id
}

/**
 * 鉴权状态：token（全局 store 订阅）+ 每标签页 sessionId。
 * token 初值从 localStorage 读（与 M1 验收页共享 'qimeng_token' 键）；
 * setToken/clearToken 走 lib/api-client 的 store（全应用同步），
 * 此处只是 React 侧订阅视图。
 */
export function useAuthState(): {
  token: string | null
  sessionId: string
  setToken: (token: string) => void
  clearToken: () => void
} {
  const token = useSyncExternalStore(subscribeToken, getToken)
  const [sessionId] = useState(ensureSessionId)

  const setToken = useCallback((value: string) => storeToken(value), [])
  const clearToken = useCallback(() => clearStoredToken(), [])

  return { token, sessionId, setToken, clearToken }
}

/**
 * 订阅全局鉴权失败事件（401/403 广播，AuthGate 据此清 token 回门禁）。
 * listener 存 ref：调用方传内联箭头（每次渲染都是新引用）也不会反复订阅/解绑。
 */
export function useOnAuthFailed(onFailed: () => void): void {
  // ref 桥接最新闭包：订阅 effect 只跑一次，事件触发时读 ref 拿调用方当前回调
  const listenerRef = useRef(onFailed)
  useEffect(() => {
    listenerRef.current = onFailed
  })
  // onAuthFailed 返回解绑函数，正好作为 effect cleanup（卸载即退订）
  useEffect(() => onAuthFailed(() => listenerRef.current()), [])
}

/** 首次初始化（设置管理密码）：POST /auth/setup，成功返回一次性 token；409 = 已初始化 */
export function useAuthSetup() {
  return useMutation({
    mutationFn: (password: string) =>
      unwrapSdkResult(postApiV1AuthSetup({ body: { password } })),
  })
}

/** 校验当前 token：POST /auth/verify，204 = 有效；401 = 无效（LoginGate 据此提示） */
export function useAuthVerify() {
  return useMutation({
    mutationFn: () => unwrapSdkResult(postApiV1AuthVerify()),
    // verify 失败不重试：401 是业务预期（token 无效），重试无意义
    retry: 0,
  })
}

/** 密码登录：POST /auth/login，密码换新 token（换设备/清缓存后的找回通道）；401 = 密码错误 */
export function useAuthLogin() {
  return useMutation({
    mutationFn: (password: string) =>
      unwrapSdkResult(postApiV1AuthLogin({ body: { password } })),
    // 登录失败不重试：401 是业务预期（密码错误），重试无意义
    retry: 0,
  })
}

/**
 * 开发模式免密登录：POST /auth/dev-login。
 * 仅服务端 config/auth_dev_mode=true 时可用；404 = 未开启（生产零行为
 * 变化），LoginGate 据此静默回退正常 setup/login 表单。用户约定：
 * 项目未完成前不要密码流程，调试 UI 直接用。
 */
export function useDevLogin() {
  return useMutation({
    mutationFn: () => unwrapSdkResult(postApiV1AuthDevLogin()),
    // 404/409 是业务预期（dev 模式未开启），重试无意义
    retry: 0,
  })
}
