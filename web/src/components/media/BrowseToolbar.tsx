import type { ReactNode } from 'react'

/** 浏览页共享的紧凑标题与就近操作；数量与动作均由消费页提供。 */
export function BrowseToolbar({ title, summary, actions }: { title: string; summary: string; actions?: ReactNode }) {
  return <div className="browse-toolbar"><div className="browse-toolbar-heading"><h2>{title}</h2><span aria-live="polite">{summary}</span></div>{actions && <div className="browse-toolbar-actions">{actions}</div>}</div>
}
