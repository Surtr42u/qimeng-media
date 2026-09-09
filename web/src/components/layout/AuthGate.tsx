import { Outlet } from 'react-router'
import { useAuthState, useOnAuthFailed } from '@/hooks/use-session'
import { useEventLedgerFlusher } from '@/hooks/use-event-ledger'
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

  // 订阅细节封装在 useOnAuthFailed（组件不 import api-client，分层纪律）
  useOnAuthFailed(() => clearToken())

  // 打点本地账补发触发通道（任务L L5）：挂载即补发 + online/回可见/周期兜底；
  // 全部事件转发收敛在 hook，组件零业务规则
  useEventLedgerFlusher()

  if (!token) return <LoginGate />
  return <Outlet />
}
