import { Outlet } from 'react-router'
import { SseBridge } from '@/components/layout/SseBridge'
import { useMaintenanceSync } from '@/hooks/use-maintenance-sync'

/**
 * 鉴权后根布局（RouterLayout）：应用级数据事件桥 + 子路由出口。
 * 与 AuthGate 分离的原因：AuthGate 只做"放行/门禁"决定，不掺业务；
 * SseBridge 只在鉴权后挂载（SSE 请求带 Bearer 头，未鉴权时应避免建立连接）。
 */
export function RootLayout() {
  useMaintenanceSync()
  return (
    <>
      <SseBridge />
      <Outlet />
    </>
  )
}
