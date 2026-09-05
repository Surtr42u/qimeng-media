import * as React from 'react'
import { Slider as SliderPrimitive } from 'radix-ui'
import { cn } from '@/lib/utils'

/**
 * 滑杆（radix Slider 行为层封装，confirm-dialog 同口径：radix 只出行为，
 * 视觉走 prototype.css 追加段 .slider-*，全 token 零颜色字面量）。
 * 设置页推荐偏好 9 维消费（替换 input[type=range]）：受控
 * value（number[]）/onValueChange 由调用方接线；方向键 / Home/End /
 * aria-valuenow / 拖拽语义由 radix 自带。
 */
function Slider({ className, ...props }: React.ComponentProps<typeof SliderPrimitive.Root>) {
  const thumbCount = Array.isArray(props.value) ? props.value.length : 1
  return (
    <SliderPrimitive.Root
      data-slot="slider"
      className={cn('slider-root', className)}
      {...props}
    >
      <SliderPrimitive.Track className="slider-track">
        <SliderPrimitive.Range className="slider-range" />
      </SliderPrimitive.Track>
      {Array.from({ length: thumbCount }, (_, i) => (
        <SliderPrimitive.Thumb key={i} className="slider-thumb" />
      ))}
    </SliderPrimitive.Root>
  )
}

export { Slider }
