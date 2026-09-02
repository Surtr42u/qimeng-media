import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router'
import { Toaster } from '@/components/ui/sonner'
import { router } from './router'
import { initTheme } from './lib/theme'
import './index.css'
// 原型样式层（媒体库 UI 主题）：必须在 index.css 之后导入——原型 :root 的
// --qm-primary/--border 与 tokens.css/index.css 同名变量按导入顺序覆盖，
// 保证 UI 呈现与用户验收的原型逐像素一致
import './styles/prototype.css'
import './pwa'

/**
 * 暗色模式（2026-09-02 随 UI 原型移植调整）：手动切换优先（侧栏月亮按钮，
 * localStorage 持久化），无手动选择时跟随系统；见 lib/theme.ts。
 * 首帧防闪白在 index.html 的内联脚本完成（先于应用代码）。
 */
initTheme()

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
