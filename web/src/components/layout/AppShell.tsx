import { NavLink, Outlet, useLocation } from 'react-router'
import { Album, ChartColumn, Home, LayoutGrid, Settings } from 'lucide-react'
import { InstallButton } from '@/components/misc/InstallButton'
import { NetworkBanner } from '@/components/misc/NetworkBanner'

/** 底部导航 Tab 定义：路径/文案/图标。图标需存在于 lucide-react（已逐个验证导出）。 */
const NAV_TABS = [
  { to: '/', label: '首页', end: true, icon: Home },
  { to: '/all', label: '全部', end: false, icon: LayoutGrid },
  { to: '/albums', label: '相册', end: false, icon: Album },
  { to: '/stats', label: '统计', end: false, icon: ChartColumn },
  { to: '/admin', label: '管理', end: false, icon: Settings },
] as const

/**
 * 应用外壳：顶部品牌栏 + 主体 Outlet + 底部 5 位 Tab 导航。
 *
 * Tab 切换不重建页面的约定（交互规格）：
 * 路由按需挂载/卸载是 react-router 的默认行为，"状态不丢"由页面层保证——
 * 列表/筛选/搜索状态一律存 URL searchParams（刷新不丢、Tab 切走再回来也能恢复），
 * 页面内部运行中的状态（如播放进度）由页面自行缓存在模块级/组件外。
  * 点击当前已激活 Tab = 回顶（滚动条回到顶部，移动端常见习惯）。
 */
export function AppShell() {
  const { pathname } = useLocation()

  return (
    <div className="flex min-h-dvh flex-col bg-background text-foreground">
      {/* 顶部品牌栏：sticky 保证滚动中随时可返回顶部 / 操作安装
          pt 额外加 safe-area（刘海屏 PWA 全屏场景），见 index.html viewport-fit=cover */}
      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-[var(--qm-divider)] bg-[var(--qm-surface)] px-4 pt-[env(safe-area-inset-top)] shadow-sm">
        <span className="text-base font-bold tracking-wide">绮梦影库</span>
        <InstallButton />
      </header>

      <NetworkBanner />

      {/* pb-nav-height：为固定底栏让出空间，避免最后一行内容被遮挡 */}
      <main className="flex-1 overflow-y-auto pb-24">
        <Outlet />
      </main>

      {/* 底部 Tab：fixed 不随内容滚动；pb 适配 iOS 底部横条 */}
      <nav
        className="fixed inset-x-0 bottom-0 z-20 border-t border-[var(--qm-divider)] bg-[var(--qm-surface)]/95 backdrop-blur"
        style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}
      >
        <div className="grid grid-cols-5">
          {NAV_TABS.map((tab) => {
            const isCurrent = tab.end ? pathname === tab.to : pathname.startsWith(tab.to)
            return (
              <NavLink
                key={tab.to}
                to={tab.to}
                end={tab.end}
                onClick={() => {
                  if (isCurrent) window.scrollTo({ top: 0, behavior: 'smooth' })
                }}
                className="flex flex-col items-center gap-0.5 py-2 text-xs"
              >
                {({ isActive }) => (
                  <>
                    <tab.icon
                      className="size-5"
                      style={{ color: isActive ? 'var(--qm-primary)' : 'var(--qm-text-muted)' }}
                    />
                    <span
                      style={{
                        color: isActive ? 'var(--qm-primary)' : 'var(--qm-text-muted)',
                        fontWeight: isActive ? 600 : 400,
                      }}
                    >
                      {tab.label}
                    </span>
                  </>
                )}
              </NavLink>
            )
          })}
        </div>
      </nav>
    </div>
  )
}
