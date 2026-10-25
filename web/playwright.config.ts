import { defineConfig } from '@playwright/test'

// 只启动静态预览，全部 API 在浏览器拦截为合成响应，绝不连接开发代理或真实服务。
const PREVIEW_PORT = 19471
const PREVIEW_URL = `http://127.0.0.1:${PREVIEW_PORT}`
export default defineConfig({
  testDir: './e2e',
  workers: 1,
  forbidOnly: true,
  use: { baseURL: PREVIEW_URL, serviceWorkers: 'block', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  webServer: {
    command: `npm run preview -- --host 127.0.0.1 --port ${PREVIEW_PORT} --strictPort`,
    url: PREVIEW_URL,
    reuseExistingServer: false,
  },
})
