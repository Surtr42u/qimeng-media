import type { ReactNode } from 'react'

/**
 * 原型胶囊按钮（prototype.css .pill 体系）：收拢各页散落的
 * `pill${active ? ' active' : ''}` 模板拼接，渲染
 * <button className={`pill${active ? ' active' : ''}`} type="button">。
 * 组件只做 className 收拢，不带 tailwind；类名字面量与既有拼接逐字节等价。
 * 变体类（.pill-tag/.pill-add 等）经 className 传入——与 base 拼接为
 * `pill <variant>${active ? ' active' : ''}`，禁止再在页面手写模板拼接。
 * 注意 .stype / .sort-pill 是不同类名体系，不归本组件管。
 */
export function Pill({
  active,
  disabled,
  onClick,
  title,
  className,
  children,
}: {
  active?: boolean
  disabled?: boolean
  onClick?: () => void
  title?: string
  /** 变体类名（如 pill-tag/pill-add），与 base 类拼接；不含 active 态 */
  className?: string
  children: ReactNode
}) {
  return (
    <button
      className={`pill${className ? ` ${className}` : ''}${active ? ' active' : ''}`}
      type="button"
      disabled={disabled}
      onClick={onClick}
      title={title}
    >
      {children}
    </button>
  )
}
