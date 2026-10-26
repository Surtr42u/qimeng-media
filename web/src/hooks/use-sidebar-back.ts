import { useLayoutEffect, useRef } from 'react'
import { useLocation, useNavigate, useNavigationType } from 'react-router'
import { HOME_PATH } from '@/lib/route-keys'

/** 仅保存壳内可确认的历史键，限制长会话内存；不依赖浏览器全局历史长度或 Router 私有 state。 */
const MAX_BACK_ENTRIES = 64

/** 首页是返回终点；直达/刷新后的未知前序历史回落首页，不退出应用。 */
export function useSidebarBack() {
  const location = useLocation()
  const navigationType = useNavigationType()
  const navigate = useNavigate()
  const history = useRef({ keys: [location.key], index: 0 })

  useLayoutEffect(() => {
    const current = history.current
    if (location.pathname === HOME_PATH) {
      history.current = { keys: [location.key], index: 0 }
    } else if (current.keys[current.index] !== location.key) {
      if (navigationType === 'PUSH') {
        const keys = [...current.keys.slice(0, current.index + 1), location.key].slice(-MAX_BACK_ENTRIES)
        history.current = { keys, index: keys.length - 1 }
      } else if (navigationType === 'REPLACE') {
        current.keys[current.index] = location.key
      } else {
        const index = current.keys.indexOf(location.key)
        history.current = index < 0 ? { keys: [location.key], index: 0 } : { ...current, index }
      }
    }
  }, [location.key, location.pathname, navigationType])

  const atHome = location.pathname === HOME_PATH
  return {
    atHome,
    back() {
      if (atHome) return
      if (history.current.index > 0) void navigate(-1)
      else void navigate(HOME_PATH, { replace: true })
    },
  }
}
