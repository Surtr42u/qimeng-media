import * as React from 'react'
import { Popover as PopoverPrimitive } from 'radix-ui'
import { cn } from '@/lib/utils'

/**
 * 弹出层（radix Popover 行为层封装，confirm-dialog 同口径：radix 只出行为，
 * 样式由调用方 className 指回 prototype.css 既有/追加类，组件层零颜色字面量）。
 * 点外关闭 / ESC 关闭 / 定位（side/align/sideOffset）由 radix 自带；
 * TopBar 搜索下拉消费（替换手写 div.search-pop + document click 监听）。
 */
function Popover({ ...props }: React.ComponentProps<typeof PopoverPrimitive.Root>) {
  return <PopoverPrimitive.Root data-slot="popover" {...props} />
}

function PopoverAnchor({ ...props }: React.ComponentProps<typeof PopoverPrimitive.Anchor>) {
  return <PopoverPrimitive.Anchor data-slot="popover-anchor" {...props} />
}

function PopoverContent({
  className,
  align = 'center',
  sideOffset = 8,
  ...props
}: React.ComponentProps<typeof PopoverPrimitive.Content>) {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Content
        data-slot="popover-content"
        align={align}
        sideOffset={sideOffset}
        className={cn(className)}
        {...props}
      />
    </PopoverPrimitive.Portal>
  )
}

export { Popover, PopoverAnchor, PopoverContent }
