/**
 * PWA Service Worker 注册（vite-plugin-pwa 客户端入口）。
 *
 * import 方式说明：'virtual:pwa-register' 是插件提供的虚拟模块（vite 构建时注入），
 * 类型由 src/vite-env.d.ts 的 `/// <reference types="vite-plugin-pwa/client" />` 引入。
 * 生产构建时虚拟模块指向插件生成的 registerSW 实现；dev 模式（devOptions.enabled=false）
 * 下该模块为空实现，不会报错。
 */
import { registerSW } from 'virtual:pwa-register'

/**
 * autoUpdate 模式（与 vite.config.ts registerType 对应）：
 * 检测到新版本时后台下载并立即接管，无需用户点击"更新"，
 * 叠加 optimizePreload 默认关闭——应用壳静态资源由 workbox 预缓存。
 * immediate: true = 页面加载即注册（不等 idle），首次安装更快可用。
 */
registerSW({ immediate: true })
