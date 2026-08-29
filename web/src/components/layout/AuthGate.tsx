import { useEffect } from 'react'
import { Outlet } from 'react-router'
import { onAuthFailed } from '@/lib/api-client'
import { useAuthState } from '@/hooks/use-session'
import { LoginGate } from '@/components/auth/LoginGate'

/**
 * 路由最外层鉴权门禁（RootLayout）。
 *
 * 进入逻辑：有 token → 放行（token 有效性由首个实际请求校验，避免每次冷启动
 * 额外打一次 /auth/verify 拖慢首屏）；无 token → LoginGate。
 * 失效逻辑：任何请求 401/403（含 SSE，见 lib/sse.ts）→ 全局 onAuthFailed 事件
 * → 清 token → 本组件同步重渲染回门禁。
 *
 * 为什么不把验证放这里统一做：媒体请求本身就会验签，冷启动多一次 verify
 * 是纯开销；且 verify 豁免了 401 事件（避免门禁表单循环），二者职责独立。
 */
export function AuthGate() {
  const { token, clearToken } = useAuthState()

  useEffect(() => onAuthFailed(() => clearToken()), [clearToken])

  if (!token) return <LoginGate />
  return <Outlet />
}
