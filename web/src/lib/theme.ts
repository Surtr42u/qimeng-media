/**
 * 明暗主题切换（原型侧栏月亮按钮语义）。
 *
 * 与 main.tsx 旧逻辑的差异：旧版"仅跟随系统"（无手动入口），原型验收改为
 * 手动切换（用户拍板）——手动选择持久化 localStorage 且优先于系统；
 * 无手动选择时保持跟随系统（运行中改系统主题仍即时生效）。
 * .dark class 与 shadcn/主题变量层共用（index.css @custom-variant dark），切 class 即全站生效。
 */

const THEME_STORAGE_KEY = 'qimeng_theme'

/** theme-color meta 的两档值（与 index.html 内联脚本双写，改动须两处同步） */
const THEME_COLOR = { light: '#ffffff', dark: '#0f0f0f' } as const

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

/** 应用指定主题并持久化为手动选择（此后不再跟随系统） */
export function applyTheme(choice: ThemeChoice): void {
  document.documentElement.classList.toggle('dark', choice === 'dark')
  localStorage.setItem(THEME_STORAGE_KEY, choice)
  syncThemeColor(choice === 'dark')
}

/** 月亮按钮：在当前 class 基础上取反并持久化 */
export function toggleTheme(): ThemeChoice {
  const next: ThemeChoice = isDark() ? 'light' : 'dark'
  applyTheme(next)
  return next
}

/**
 * 应用启动时调用（main.tsx）：手动选择优先（刷新后恢复用户上次点击的明暗），
 * 否则跟随系统；系统主题变化的监听仅在"无手动选择"期间生效（手动后系统变化不再覆盖）。
 */
export function initTheme(): void {
  const syncFromSystem = (): void => {
    if (readStoredTheme() !== null) return
    const matches = window.matchMedia('(prefers-color-scheme: dark)').matches
    document.documentElement.classList.toggle('dark', matches)
  }
  // 手动选择存在时先恢复它（bug 修复：此前只跟随系统，刷新后手动选择被丢弃）
  const stored = readStoredTheme()
  if (stored !== null) {
    document.documentElement.classList.toggle('dark', stored === 'dark')
    syncThemeColor(stored === 'dark')
  }
  syncFromSystem()
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', syncFromSystem)
}
