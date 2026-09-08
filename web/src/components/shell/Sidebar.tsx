/**
 * 左侧窄栏（64px）：返回按钮 + 主导航 + 底部图标组（主题/维护/设置）。
 * 类名与原型一致（styles/prototype.css）；active 高亮由当前路由驱动
 * （原型是 JS 切 active class，路由化后由 pathname 推导，语义等价）。
 */

import { useLocation, useNavigate } from 'react-router'
import { ALBUMS_PATH, HOME_PATH } from '@/lib/route-keys'
import { toggleTheme } from '@/lib/theme'
import {
  AlbumsIcon, BackIcon, DataIcon, HomeIcon, MineIcon, MoonIcon, SettingsIcon, WrenchIcon,
} from './icons'

const MAIN_NAV = [
  // F2/F5 审查清偿：主导航路由串全走 route-keys 常量（路由键唯一来源）
  { to: HOME_PATH, label: '首页', Icon: HomeIcon },
  { to: ALBUMS_PATH, label: '相册', Icon: AlbumsIcon },
  { to: '/app/mine', label: '我的', Icon: MineIcon },
  { to: '/app/data', label: '数据', Icon: DataIcon },
] as const

export function Sidebar() {
  const navigate = useNavigate()
  const { pathname } = useLocation()

  return (
    <aside className="sidebar">
      <button
        className="sidebar--back"
        title="返回"
        type="button"
        onClick={() => navigate(-1)}
      >
        <BackIcon />
      </button>
      <nav className="sidebar--pages">
        {MAIN_NAV.map(({ to, label, Icon }) => (
          <a
            key={to}
            className={`nav-item${pathname === to ? ' active' : ''}`}
            onClick={() => navigate(to)}
          >
            <span className="nav-icon">
              <Icon />
            </span>
            <p>{label}</p>
          </a>
        ))}
      </nav>
      <ul className="sidebar--settings">
        <li className="settings-item" title="明暗主题" onClick={toggleTheme}>
          <MoonIcon />
        </li>
        <li className="settings-item" title="维护" onClick={() => navigate('/app/maintenance')}>
          <WrenchIcon />
        </li>
        <li className="settings-item" title="设置" onClick={() => navigate('/app/settings')}>
          <SettingsIcon />
        </li>
      </ul>
    </aside>
  )
}
