/**
 * 左侧窄栏（64px）+ B 站式悬浮展开面板（2026-09-17 用户拍板，参照其提供的
 * B 站客户端截图：悬停窄栏滑出悬浮面板盖在内容上，圆角+阴影，内容不位移）。
 * - 窄栏本体不变：返回按钮 + 主导航（图标+短标签）+ 底部图标组；
 * - 悬停窄栏滑出 `.sidebar--flyout`（绝对定位 left:100%，行式导航带完整标签），
 *   面板内「<」收起钮可关闭并抑制本次悬停重开（鼠标离栏一次后恢复）；
 * - 类名与样式见 styles/prototype.css「B 站式悬浮侧边栏」节。
 */

import { useRef, useState } from 'react'
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

/** 悬停离开后延迟关面板（ms）：给「栏 → 面板」跨移动留宽限，避免抖动 */
const FLYOUT_CLOSE_DELAY_MS = 180

export function Sidebar() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [flyoutOpen, setFlyoutOpen] = useState(false)
  // 「<」收起后的抑制标记：True 时悬停不再重开，鼠标离栏一次即复位（否则收起钮无意义）
  const [suppressed, setSuppressed] = useState(false)
  const closeTimer = useRef<number | null>(null)

  const enterRail = () => {
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current)
      closeTimer.current = null
    }
    if (!suppressed) setFlyoutOpen(true)
  }
  const leaveRail = () => {
    closeTimer.current = window.setTimeout(() => {
      setFlyoutOpen(false)
      setSuppressed(false)
    }, FLYOUT_CLOSE_DELAY_MS)
  }
  const collapse = () => {
    setFlyoutOpen(false)
    setSuppressed(true)
  }
  const go = (to: string) => {
    navigate(to)
    setFlyoutOpen(false)
  }

  return (
    <aside className="sidebar" onMouseEnter={enterRail} onMouseLeave={leaveRail}>
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
            onClick={() => go(to)}
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

      {/* 悬浮展开面板：绝对定位盖在内容上（left:100%），不挤占布局 */}
      <div className={`sidebar--flyout${flyoutOpen ? ' open' : ''}`} aria-hidden={!flyoutOpen}>
        <div className="flyout-head">
          <button className="flyout-collapse" title="收起" type="button" onClick={collapse}>
            <BackIcon />
          </button>
        </div>
        <nav className="flyout-pages">
          {MAIN_NAV.map(({ to, label, Icon }) => (
            <a
              key={to}
              className={`flyout-item${pathname === to ? ' active' : ''}`}
              onClick={() => go(to)}
            >
              <Icon />
              <span>{label}</span>
            </a>
          ))}
        </nav>
        <ul className="flyout-settings">
          <li className="flyout-item" onClick={toggleTheme}>
            <MoonIcon />
            <span>明暗主题</span>
          </li>
          <li
            className="flyout-item"
            onClick={() => {
              navigate('/app/maintenance')
              setFlyoutOpen(false)
            }}
          >
            <WrenchIcon />
            <span>维护</span>
          </li>
          <li
            className="flyout-item"
            onClick={() => {
              navigate('/app/settings')
              setFlyoutOpen(false)
            }}
          >
            <SettingsIcon />
            <span>设置</span>
          </li>
        </ul>
      </div>
    </aside>
  )
}
