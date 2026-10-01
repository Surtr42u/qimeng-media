/**
 * 明暗主题切换（侧栏月亮按钮语义；ADR-0031 起暗色优先）。
 *
 * v1 行为：手动选择持久化优先，无手动选择时跟随系统。
 * v2 行为（暗色优先的媒体消费场景拍板）：手动选择持久化仍优先；**无手动选择时
 * 默认暗色**（媒体库消费场景的主形态），不再跟随系统——浅色是完整但次要的
 * 「晨雾玻璃」变体，用户可随时月亮按钮切过去并持久化。
 * .dark class 与 shadcn/主题变量层共用（index.css @custom-variant dark），切 class 即全站生效。
 */

const THEME_STORAGE_KEY = 'qimeng_theme'

/** theme-color meta 的两档值（= tokens.css 画布 --qm-bg 的 hex 形态；
 *  与 index.html 内联脚本双写，改动须两处同步） */
const THEME_COLOR = { light: '#f3f3f9', dark: '#0b0c16' } as const

function syncThemeColor(dark: boolean): void {
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', dark ? THEME_COLOR.dark : THEME_COLOR.light)
}

export type ThemeChoice = 'light' | 'dark'

function readStoredTheme(): ThemeChoice | null {
  const v = localStorage.getItem(THEME_STORAGE_KEY)
  return v === 'light' || v === 'dark' ? v : null
}

/** 当前是否深色（以 .dark class 为准——class 是全站唯一切换面） */
export function isDark(): boolean {
  return document.documentElement.classList.contains('dark')
}

/**
 * 切 class 并同步 theme-color meta；手动选择由调用方决定是否持久化。
 * 支持 View Transition 的浏览器（Chromium 111+）用 document.startViewTransition
 * 做全屏交叉溶解——明暗切换从硬切变成液体玻璃式的柔化过渡；不支持的浏览器
 * 同步直切（渐进增强，零降级成本）。
 */
export function applyTheme(choice: ThemeChoice, persist = true): void {
  const swap = (): void => {
    document.documentElement.classList.toggle('dark', choice === 'dark')
    syncThemeColor(choice === 'dark')
  }
  if (persist) localStorage.setItem(THEME_STORAGE_KEY, choice)
  const doc = document as Document & { startViewTransition?: (cb: () => void) => unknown }
  if (typeof doc.startViewTransition === 'function') {
    doc.startViewTransition(swap)
  } else {
    swap()
  }
}

/** 月亮按钮：在当前 class 基础上取反并持久化 */
export function toggleTheme(): ThemeChoice {
  const next: ThemeChoice = isDark() ? 'light' : 'dark'
  applyTheme(next)
  return next
}

/**
 * 应用启动时调用（main.tsx）：手动选择优先（刷新后恢复用户上次点击的明暗），
 * 否则暗色（ADR-0031 暗色优先；本函数只兜底，首帧防闪白已由 index.html 内联
 * 脚本先行处理，两处语义须一致）。
 */
export function initTheme(): void {
  const stored = readStoredTheme()
  const dark = stored !== null ? stored === 'dark' : true
  document.documentElement.classList.toggle('dark', dark)
  syncThemeColor(dark)
}
