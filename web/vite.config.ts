import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      // @ 指向 src/：shadcn/ui 生成组件统一用 @/components/ui/... 导入，与 tsconfig paths 保持一致
      // 用 import.meta.dirname 而非 __dirname：ESM 下标准写法，Vite 8 原生配置加载器只认前者
      '@': path.join(import.meta.dirname, 'src'),
    },
  },
})
