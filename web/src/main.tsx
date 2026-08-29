import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router'
import { Toaster } from '@/components/ui/sonner'
import { router } from './router'
import './index.css'
import './pwa'

/**
 * 暗色模式：仅跟随系统（旧版项目决策——不提供手动切换入口，界面风格由系统设置统一）。
 * 首帧防闪白在 index.html 的内联脚本完成（先于应用代码），这里只负责后续
 * 系统切换同步（用户运行中改系统主题时即时生效）。
 */
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)')
function applySystemTheme(matches: boolean): void {
  document.documentElement.classList.toggle('dark', matches)
}
applySystemTheme(darkQuery.matches)
darkQuery.addEventListener('change', (event) => applySystemTheme(event.matches))

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
      staleTime: 20_000,
      retry: 1,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* M2：M0 骨架（App.tsx 单页）被路由骨架取代，直接挂 RouterProvider */}
      <RouterProvider router={router} />
      {/* 全局 toast（sonner）：主题变量已由 shadcn 生成映射，无需额外配置 */}
      <Toaster richColors position="bottom-center" />
    </QueryClientProvider>
  </StrictMode>,
)
