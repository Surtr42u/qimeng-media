import { useEffect, useState } from 'react'
import { WifiOff } from 'lucide-react'

/** 离线提示条（navigator.onLine 事件驱动，不依赖 SW——dev 模式同样有效） */
export function NetworkBanner() {
  const [offline, setOffline] = useState(() => !navigator.onLine)

  useEffect(() => {
    const onOnline = () => setOffline(false)
    const onOffline = () => setOffline(true)
    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)
    return () => {
      window.removeEventListener('online', onOnline)
      window.removeEventListener('offline', onOffline)
    }
  }, [])

  if (!offline) return null

  return (
    <div className="flex items-center justify-center gap-2 bg-[var(--qm-chip-bg)] py-1 text-xs text-[var(--qm-text-muted)]">
      <WifiOff className="size-3.5" />
      网络已断开，当前展示可能不是最新内容
    </div>
  )
}
