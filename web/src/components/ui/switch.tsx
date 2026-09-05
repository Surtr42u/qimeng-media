import * as React from 'react'
import { Switch as SwitchPrimitive } from 'radix-ui'
import { cn } from '@/lib/utils'

/**
 * 开关（radix Switch 行为层封装，confirm-dialog 同口径：radix 只出行为，
 * 视觉走 prototype.css 追加段 .settings-switch-track/--thumb——逐值复刻被替换的
 * 手写隐藏 checkbox + <i> 滑块配方）。受控 checked/onCheckedChange 由调用方接线；
 * Space 切换 / 焦点环 / role=switch + aria-checked / label 点击联动由 radix 自带。
 * 组件层零颜色字面量。
 */
function Switch({ className, ...props }: React.ComponentProps<typeof SwitchPrimitive.Root>) {
  return (
    <SwitchPrimitive.Root
      data-slot="switch"
      className={cn('settings-switch-track', className)}
      {...props}
    >
      <SwitchPrimitive.Thumb className="settings-switch-thumb" />
    </SwitchPrimitive.Root>
  )
}

export { Switch }
