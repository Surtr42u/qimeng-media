import { createBrowserRouter } from 'react-router'
import { AuthGate } from '@/components/layout/AuthGate'
import { RootLayout } from '@/components/layout/RootLayout'

/**
 * 路由骨架（2026-09-01：v1 旧壳与 v2 panel-demo 均已删除）。
 *
 * 当前状态：
 * - UI 主路线 = `media-ui-prototype/`（静态原型，独立访问 http://127.0.0.1:8099/）；
 * - web 端仅保留鉴权基建（AuthGate / RootLayout / SseBridge / LoginGate）与
 *   生成 SDK（api/）、共享工具（lib/），待原型敲定后移植功能重建页面。
 */

/** 占位页：web 端暂无业务页面（UI 主路线在静态原型），仅提示入口 */
function UiPlaceholder() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background text-foreground">
      <div className="text-center">
        <p className="text-lg font-semibold">绮梦影库</p>
        <p className="mt-2 text-sm text-muted-foreground">
          UI 主路线为 media-ui-prototype 静态原型，web 端页面待原型敲定后重建。
        </p>
      </div>
    </main>
  )
}

export const router = createBrowserRouter([
  // 默认入口：占位页（web 端暂无业务页面）
  { index: true, element: <UiPlaceholder /> },
  {
    // AuthGate：鉴权门禁（登录流程基建保留），后续页面挂这里
    path: 'app',
    element: <AuthGate />,
    children: [
      {
        // 鉴权后内容层：事件桥 + 路由出口
        element: <RootLayout />,
        children: [],
      },
    ],
  },
])
