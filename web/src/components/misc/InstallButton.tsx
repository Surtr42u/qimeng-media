import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Download } from 'lucide-react'

/**
 * PWA 安装提示按钮。
 * 只依赖 beforeinstallprompt 事件（Chromium 系）：
 * - iOS Safari 不支持 beforeinstallprompt → 按钮自然隐藏（iOS 用户请用"添加到主屏幕"）
 * - 已安装（appinstalled）/ 事件未触发（站点无安装条件，如 HTTP）→ 隐藏
 */
export function InstallButton() {
  const [deferredEvent, setDeferredEvent] = useState<BeforeInstallPromptEvent | null>(null)

  useEffect(() => {
    const onPrompt = (event: Event) => {
      // preventDefault：Chrome 不再自动弹横幅，显示时机由应用决定（这里是 header 按钮）
      event.preventDefault()
      setDeferredEvent(event as BeforeInstallPromptEvent)
    }
    const onInstalled = () => setDeferredEvent(null)
    window.addEventListener('beforeinstallprompt', onPrompt)
    window.addEventListener('appinstalled', onInstalled)
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt)
      window.removeEventListener('appinstalled', onInstalled)
    }
  }, [])

  if (!deferredEvent) return null

  const handleInstall = () => {
    void deferredEvent.prompt()
    setDeferredEvent(null) // 同一事件只能 prompt 一次；结果以 appinstalled 为准
  }

  return (
    <Button variant="ghost" size="sm" onClick={handleInstall}>
      <Download className="size-4" />
      安装到设备
    </Button>
  )
}

/** beforeinstallprompt 不在标准 DOM lib 里，按 Chromium 实际事件形状声明（只声明本组件用到的字段） */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
}
