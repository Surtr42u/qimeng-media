import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'

// 开发代理目标：Go 服务端默认监听端口（docs/PROJECT_PLAN.md / server config 默认 :8420）。
// 隔离调试实例可用 QIMENG_DEV_PROXY_TARGET 覆盖（如 127.0.0.1:18499 的合成测试库），
// 未设置时行为与旧配置完全一致；仅 dev server 生效，不进生产构建。
const DEV_SERVER_PORT = 8420
const PROXY_TARGET = process.env.QIMENG_DEV_PROXY_TARGET ?? `http://127.0.0.1:${DEV_SERVER_PORT}`

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      // 自动更新：SW 有新版本时后台下载并立即接管，无需用户确认。
      // 选择 autoUpdate 的原因：媒体库是个人使用场景，追求"升级无感"，
      // notifyUpdate（提示更新）会打断浏览体验。
      registerType: 'autoUpdate',
      // devOptions.enabled 会在 dev 模式下也注册 SW，便于真机验证 PWA 行为；
      // 代价是 dev 下缓存干扰——只在需要验证时打开，日常开发保持 false。
      devOptions: { enabled: false },
      manifest: {
        // 产品名用正式中文名（仓库 README.md 标题「绮梦影库·NAS 多端版」）
        name: '绮梦影库',
        short_name: '绮梦影库',
        description: 'NAS 多端媒体库 Web 客户端：媒体全存 NAS，多设备访问',
        lang: 'zh-CN',
        // theme_color/background_color 只接受具体色值（PWA 规范），不能写 CSS var()。
        // 取 tokens.css 画布 --qm-bg 的 hex 形态（ADR-0031 暗色优先：manifest 底随暗色画布；
        // 与 index.html 内联脚本 / lib/theme.ts THEME_COLOR 三处双写，改动须同步）。
        theme_color: '#0b0c16',
        background_color: '#0b0c16',
        display: 'standalone',
        start_url: '/',
        icons: [
          { src: '/pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: '/pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          {
            src: '/maskable-icon-512x512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
          { src: '/apple-touch-icon-180x180.png', sizes: '180x180', type: 'image/png' },
        ],
      },
      workbox: {
        // SPA 导航回退：任意客户端路由（如 /all）离线刷新时回退到 index.html 让路由接管
        navigateFallback: '/index.html',
        // 排除需要走后端的导航路径：/api（JSON API）、/media（签名媒体直链，本身就是
        // 一次性签名 URL，且媒体文件体积大，缓存毫无意义）、/_debug（服务端调试页）
        navigateFallbackDenylist: [/^\/api\//, /^\/media\//, /^\/_debug\//],
        // 刻意不配 runtimeCaching：
        // 1. 媒体内容（/media 签名直链）不缓存——签名 URL 有效期内才可访问、大文件缓存浪费存储；
        // 2. 应用自有静态资源（JS/CSS/字体/favicon）已由 workbox 预缓存（precache），离线可启动壳；
        // 3. 无第三方 CDN 依赖，无需运行时缓存策略。
        // 若未来引入外部字体/图标 CDN，再按需补 NetworkFirst。
      },
    }),
  ],
  resolve: {
    alias: {
      // @ 指向 src/：shadcn/ui 生成组件统一用 @/components/ui/... 导入，与 tsconfig paths 保持一致
      // 用 import.meta.dirname 而非 __dirname：ESM 下标准写法，Vite 8 原生配置加载器只认前者
      '@': path.join(import.meta.dirname, 'src'),
    },
  },
  server: {
    proxy: {
      // dev 时把 API/媒体/健康检查转发给本地 Go 服务端；生产由服务端托管 dist，同源无需代理
      '/api': { target: PROXY_TARGET, changeOrigin: true },
      '/media': { target: PROXY_TARGET, changeOrigin: true },
      '/healthz': { target: PROXY_TARGET, changeOrigin: true },
      '/readyz': { target: PROXY_TARGET, changeOrigin: true },
    },
  },
})
