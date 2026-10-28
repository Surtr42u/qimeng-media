import type { ReactNode } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import { manageReturn } from '@/lib/manage-origin'

/** 管理子页共用定位/返回入口；链接仍经过路由的未保存离开保护。 */
export function ManagePageHeader({ title, description, backTo, backLabel, actions }: {
  title: string; description: string; backTo?: string; backLabel?: string; actions?: ReactNode
}) {
  const origin = manageReturn(useLocation().search)
  return <header className="manage-page-head">
    <Link className="manage-back" to={backTo ?? origin.path}><ArrowLeft aria-hidden="true" />{backLabel ?? origin.label}</Link>
    <div className="manage-heading"><div><h2>{title}</h2><p>{description}</p></div>{actions && <div className="manage-actions">{actions}</div>}</div>
  </header>
}

export function ManageQueryError({ children, retry, busy = false }: { children: ReactNode; retry: () => void; busy?: boolean }) {
  return <div className="manage-notice manage-notice--warning" role="alert"><span>{children}</span><button className="pill" type="button" onClick={retry} disabled={busy}>重新读取</button></div>
}
