import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router'
import { Toaster } from '@/components/ui/sonner'
import { AppErrorBoundary } from '@/components/layout/AppErrorBoundary'
import { router } from './router'
import { initTheme } from './lib/theme'
import { installClientLogs } from './lib/client-logs'
import { QUERY_STALE_TIME_MS } from './lib/constants'
import './index.css'
// 全新设计语言主题层「绮梦流光 · Aurora Glass」（ADR-0031）：必须在 index.css 之后
// 导入——组件类名契约不变，视觉体系整体更换（玻璃材质/暗色优先/极光画布/现代动效）
import './styles/glass.css'
import './pwa'

/**
 * 暗色模式（2026-09-02 随 UI 原型移植调整）：手动切换优先（侧栏月亮按钮，
 * localStorage 持久化），无手动选择时跟随系统；见 lib/theme.ts。
 * 首帧防闪白在 index.html 的内联脚本完成（先于应用代码）。
 */
initTheme()

// 客户端异常上报器（window.onerror / unhandledrejection → POST
// /api/v1/client-logs，维护页排查表数据源）：应用一启动就挂监听。
installClientLogs()

/**
 * TanStack Query 全局客户端。
 * - staleTime 20s：媒体库数据一致性主要由 SSE 事件桥（SseBridge）主动失效保证，
 *   staleTime 只是无事件时避免高频重拉的兜底；
 * - retry 1 次：网络闪断（移动端）常见，2 次尝试足够避免雪崩；
 *   【注意】Token 失效（401）不会被重试救回——错误由 AuthGate 事件接管，
 *   重试不会导致门禁循环（拦截器在 client 层，不受 Query retry 影响）。
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: QUERY_STALE_TIME_MS,
      retry: 1,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* 全局渲染期异常兜底：路由树内任何页面抛错不再卸成白屏（见组件注释） */}
      <AppErrorBoundary>
        {/* M2：M0 骨架（App.tsx 单页）被路由骨架取代，直接挂 RouterProvider */}
        <RouterProvider router={router} />
        {/* 全局 toast（sonner）：主题变量已由 shadcn 生成映射，无需额外配置 */}
        <Toaster richColors position="bottom-center" />
      </AppErrorBoundary>
    </QueryClientProvider>
  </StrictMode>,
)
