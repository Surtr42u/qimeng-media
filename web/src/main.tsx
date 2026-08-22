import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import App from './App.tsx'

/**
 * TanStack Query 全局客户端：M0 只接线证明链路。
 * 实际的数据获取/缓存配置（staleTime、重试策略等）待接入 make sdk 生成的
 * API 客户端后，在 src/hooks 层按需完善。
 */
const queryClient = new QueryClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
