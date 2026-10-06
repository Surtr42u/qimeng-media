/**
 * 极光空态/失败态组件（EmptyState）
 *
 * 为列表为空、搜索无果、网络错误提供统一的具有「绮梦流光」质感的占位面板。
 * 纯展示组件，遵循设计 token 与 Liquid Glass 规范。
 */

import type { ReactNode } from 'react'

interface EmptyStateProps {
  /** 核心提示标题或主文案 */
  title: ReactNode
  /** 次要描述说明（可选） */
  description?: ReactNode
  /** 自定义图标或装饰（可选） */
  icon?: ReactNode
  /** 操作区（如「重试」或「清空筛选」按钮） */
  action?: ReactNode
  /** 紧凑模式（内联在网格下方，不加独立毛玻璃大卡片） */
  compact?: boolean
  className?: string
}

export function EmptyState({
  title,
  description,
  icon,
  action,
  compact = false,
  className = '',
}: EmptyStateProps) {
  if (compact) {
    return (
      <div className={`empty-state empty-state--compact ${className}`}>
        {icon ? <div className="empty-state--icon">{icon}</div> : null}
        <p className="empty-state--title">{title}</p>
        {description ? <p className="empty-state--desc">{description}</p> : null}
        {action ? <div className="empty-state--action">{action}</div> : null}
      </div>
    )
  }

  return (
    <div className={`empty-state-panel ${className}`} role="status">
      {icon ? <div className="empty-state--icon">{icon}</div> : null}
      <h3 className="empty-state--title">{title}</h3>
      {description ? <p className="empty-state--desc">{description}</p> : null}
      {action ? <div className="empty-state--action">{action}</div> : null}
    </div>
  )
}
