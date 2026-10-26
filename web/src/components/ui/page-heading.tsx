import type { ReactNode } from 'react'

/** 主页面共用标题层级；不混入管理子页的返回路径。 */
export function PageHeading({ title, description, actions }: { title: string; description: string; actions?: ReactNode }) {
  return <header className="workspace-heading"><div><h2>{title}</h2><p>{description}</p></div>{actions && <div className="workspace-heading-actions">{actions}</div>}</header>
}
