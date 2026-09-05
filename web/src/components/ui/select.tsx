import * as React from 'react'
import { Select as SelectPrimitive } from 'radix-ui'
import { cn } from '@/lib/utils'

/**
 * 下拉选择（radix Select 行为层封装，confirm-dialog 同口径：radix 只出行为，
 * 视觉走 prototype.css 追加段 .select-*——trigger 逐值复刻被替换的原生 <select>
 * 配方即 .f-years select）。受控 value/onValueChange 由调用方接线；
 * 方向键导航 / 回车选中 / ESC 与点外关闭 / 选中态 aria / 焦点环由 radix 自带。
 * 组件层零颜色字面量（下拉箭头 currentColor 随 token）。
 */

/** 下拉箭头（内联 SVG：不引 shell/icons，避免 ui → shell 反向引用） */
function ChevronDown() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m6 9 6 6 6-6" />
    </svg>
  )
}

function Select({ ...props }: React.ComponentProps<typeof SelectPrimitive.Root>) {
  return <SelectPrimitive.Root data-slot="select" {...props} />
}

/** trigger：固定渲染 SelectValue（placeholder 由调用方给）+ 右侧下拉箭头 */
function SelectTrigger({
  placeholder,
  className,
  ...props
}: React.ComponentProps<typeof SelectPrimitive.Trigger> & { placeholder?: string }) {
  return (
    <SelectPrimitive.Trigger
      data-slot="select-trigger"
      className={cn('select-trigger', className)}
      {...props}
    >
      <SelectPrimitive.Value placeholder={placeholder} />
      <SelectPrimitive.Icon className="select-trigger-icon">
        <ChevronDown />
      </SelectPrimitive.Icon>
    </SelectPrimitive.Trigger>
  )
}

/** 内容：popper 定位挂 trigger 下方，宽度随内容（对齐原生下拉），超长列表内部滚动 */
function SelectContent({ children, ...props }: React.ComponentProps<typeof SelectPrimitive.Content>) {
  return (
    <SelectPrimitive.Portal>
      <SelectPrimitive.Content
        data-slot="select-content"
        position="popper"
        sideOffset={4}
        className="select-content"
        {...props}
      >
        <SelectPrimitive.Viewport className="select-viewport">{children}</SelectPrimitive.Viewport>
      </SelectPrimitive.Content>
    </SelectPrimitive.Portal>
  )
}

function SelectItem({ className, children, ...props }: React.ComponentProps<typeof SelectPrimitive.Item>) {
  return (
    <SelectPrimitive.Item data-slot="select-item" className={cn('select-item', className)} {...props}>
      <SelectPrimitive.ItemText>{children}</SelectPrimitive.ItemText>
    </SelectPrimitive.Item>
  )
}

export { Select, SelectTrigger, SelectContent, SelectItem }
