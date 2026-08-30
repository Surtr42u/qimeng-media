/**
 * 管理面板原型布局：左侧固定窄导航 + 右侧内容区（顶栏显示当前页标题）。
 *
 * 独立原型说明：挂在 router 顶层（AuthGate 之外），无需登录即可预览效果；
 * 亮暗跟随现有 .dark class（main.tsx 的系统主题同步对全局生效）。
 */
import { NavLink, Outlet, useLocation } from 'react-router'
import {
  RiHome5Line,
  RiAlbumLine,
  RiUser3Line,
  RiToolsLine,
  RiSettings3Line,
} from '@remixicon/react'
import { Badge } from '@/components/tremor/badge'
import { cx } from '@/lib/tremor'
import { RANK_PAGE_TITLES, isRankKind } from './mock'

/** 品牌文案（管理面板理念：服务器控制台） */
const BRAND = { main: '绮梦影库', sub: '控制台' } as const
/** 原型标识 */
const DEMO_BADGE = '原型演示'

interface NavItem {
  to: string
  label: string
  title: string
  icon: React.ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>
  /** 是否精确匹配（index 路由避免 to 含前缀误激活） */
  end?: boolean
}

/**
 * 主导航四项（设置单独固定在侧栏最下方，与主导航之间留弹性空隙）。
 * label=侧栏文字，title=顶栏标题。
 */
const NAV_ITEMS: NavItem[] = [
  { to: '/panel-demo', label: '首页', title: '首页', icon: RiHome5Line, end: true },
  { to: '/panel-demo/gallery', label: '相册', title: '相册', icon: RiAlbumLine },
  { to: '/panel-demo/mine', label: '我的', title: '我的', icon: RiUser3Line },
  { to: '/panel-demo/maintenance', label: '维护', title: '维护', icon: RiToolsLine },
]

/** 设置项：固定在侧栏最下方（B 站客户端惯例——设置与主导航分离） */
const NAV_SETTINGS: NavItem = {
  to: '/panel-demo/settings',
  label: '设置',
  title: '设置',
  icon: RiSettings3Line,
}

/**
 * 顶栏标题：rank/:type 按榜单映射，browse/:kind 统一「内容浏览」，
 * 其余按主导航 title 回落（榜单/浏览子页不挂侧栏导航，无 current）。
 */
function resolveHeaderTitle(pathname: string): string {
  const rankMatch = /\/data\/rank\/([^/]+)/.exec(pathname)
  if (rankMatch) return isRankKind(rankMatch[1]) ? RANK_PAGE_TITLES[rankMatch[1]] : '排行榜'
  if (pathname.includes('/data/browse/')) return '内容浏览'
  const current = NAV_ITEMS.find((item) =>
    item.end ? pathname === item.to : pathname.startsWith(item.to),
  )
  return current?.title ?? '控制台'
}

/** 侧栏导航项（主导航与设置项共用同一激活态样式） */
function NavButton({ item }: { item: NavItem }) {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) =>
        cx(
          // 激活态用品牌 accent，未激活弱化为次要色
          'flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors',
          isActive
            ? 'bg-accent font-medium text-accent-foreground'
            : 'text-muted-foreground hover:bg-accent/50 hover:text-foreground',
        )
      }
    >
      <item.icon className="size-4.5 shrink-0" aria-hidden={true} />
      {item.label}
    </NavLink>
  )
}

export default function PanelLayout() {
  const { pathname } = useLocation()

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      {/* 左侧固定窄导航栏 */}
      <aside className="flex w-44 shrink-0 flex-col border-r border-border bg-sidebar">
        <div className="px-4 py-5">
          <p className="text-sm font-semibold tracking-wide">{BRAND.main}</p>
          <p className="mt-0.5 text-xs text-muted-foreground">{BRAND.sub}</p>
        </div>
        <nav className="mt-2 flex flex-1 flex-col gap-1 px-2">
          {NAV_ITEMS.map((item) => (
            <NavButton key={item.to} item={item} />
          ))}
          {/* 弹性空隙：把设置推到侧栏最下方 */}
          <div className="flex-1" aria-hidden={true} />
          <NavButton item={NAV_SETTINGS} />
        </nav>
        <div className="px-4 py-4">
          <p className="text-[11px] leading-4 text-muted-foreground">
            Tremor Raw 组件预览
            <br />
            数据均为 mock
          </p>
        </div>
      </aside>

      {/* 右侧内容区 */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center justify-between border-b border-border px-6">
          <h1 className="text-base font-semibold">{resolveHeaderTitle(pathname)}</h1>
          <Badge variant="neutral">{DEMO_BADGE}</Badge>
        </header>
        {/* 内容区限宽居中，管理后台常用阅读宽度 */}
        <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
