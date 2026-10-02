"use client"

import { Toaster as Sonner, type ToasterProps } from "sonner"
import { CircleCheckIcon, InfoIcon, TriangleAlertIcon, OctagonXIcon, Loader2Icon } from "lucide-react"
import { useIsDark } from "@/hooks/use-is-dark"

const Toaster = ({ ...props }: ToasterProps) => {
  // 主题直读应用自身（ADR-0031 暗色优先的 html.dark class，use-is-dark）。
  // 原实现经 next-themes useTheme，但项目从未挂 ThemeProvider——恒回退
  // "system" 跟随 OS 偏好，与应用 class 主题脱钩（浅色系统 + 默认暗色应用时，
  // richColors 色板/图标对比度按亮色出，浮在暗玻璃上）。next-themes 随本修复
  // 退役（2026-10-02 手搓排查：半迁移遗留，依赖树内无其他消费方）。
  const isDark = useIsDark()

  return (
    <Sonner
      theme={isDark ? "dark" : "light"}
      className="toaster group"
      icons={{
        success: (
          <CircleCheckIcon className="size-4" />
        ),
        info: (
          <InfoIcon className="size-4" />
        ),
        warning: (
          <TriangleAlertIcon className="size-4" />
        ),
        error: (
          <OctagonXIcon className="size-4" />
        ),
        loading: (
          <Loader2Icon className="size-4 animate-spin" />
        ),
      }}
      style={
        {
          "--normal-bg": "var(--popover)",
          "--normal-text": "var(--popover-foreground)",
          "--normal-border": "var(--border)",
          "--border-radius": "var(--radius)",
        } as React.CSSProperties
      }
      toastOptions={{
        classNames: {
          toast: "cn-toast",
        },
      }}
      {...props}
    />
  )
}

export { Toaster }
