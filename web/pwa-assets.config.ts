import { defineConfig, minimal2023Preset } from '@vite-pwa/assets-generator/config'

/**
 * PWA 图标生成配置：npm run generate-pwa-assets。
 * 源图 public/favicon.svg → 输出全套 PNG 到 public/（与源图同目录）。
 * 产出文件名由 minimal2023Preset 决定（pwa-64/192/512、maskable-icon-512、
 * apple-touch-icon-180、favicon.ico）；manifest（vite.config.ts）只引用 192/512/maskable/apple。
 */
export default defineConfig({
  preset: minimal2023Preset,
  images: ['public/favicon.svg'],
})
