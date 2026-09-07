import path from 'node:path'
import { defineConfig } from 'vitest/config'

/**
 * 测试专用独立配置（ADR-0017）：刻意不并入 vite.config.ts——
 * vitest.config.ts 存在时优先生效，vite 配置里的 react/tailwindcss/PWA
 * 插件链与 dev proxy 不会被加载，lib 纯函数单测无需浏览器环境与构建插件。
 * 注意 alias 与 vite.config.ts 双写：本文件存在期间 vite 的 resolve.alias
 * 不作用于测试加载器，两侧改动须互相同步。
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
  resolve: {
    alias: {
      // @ 指向 src/：与 vite.config.ts / tsconfig.app.json paths 同口径
      // （ESM 下用 import.meta.dirname，Vite 8 配置加载器只认前者）
      '@': path.join(import.meta.dirname, 'src'),
    },
  },
})
