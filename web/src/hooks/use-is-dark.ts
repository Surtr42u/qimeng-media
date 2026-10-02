/**
 * 订阅应用明暗主题（ADR-0031：`.dark` class 挂 html 上是全站唯一切换面，
 * lib/theme.ts 是唯一写入方）。为什么手写 class 监听而非主题库：主题机制按
 * ADR-0031 自持（index.html 内联脚本 + lib/theme.ts 双写、暗色优先），项目
 * 不挂 ThemeProvider；本 hook 只做 class → React 状态的单向桥，观察器模式与
 * components/media/video-player.tsx 的主题色 MutationObserver 同款。
 */

import { useEffect, useState } from 'react'

export function useIsDark(): boolean {
  const [isDark, setIsDark] = useState(() =>
    document.documentElement.classList.contains('dark'),
  )
  useEffect(() => {
    const observer = new MutationObserver(() => {
      setIsDark(document.documentElement.classList.contains('dark'))
    })
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    })
    return () => observer.disconnect()
  }, [])
  return isDark
}
