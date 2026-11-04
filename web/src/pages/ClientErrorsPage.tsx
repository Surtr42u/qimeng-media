import { Link } from 'react-router'
import { Pill } from '@/components/ui/pill'
import { useClientLogs } from '@/hooks/use-client-logs'
import { formatDateTime } from '@/lib/format'
import { MAINTENANCE_PATH } from '@/lib/route-keys'

const LOG_LEVEL_LABELS: Record<string, string> = { error: '错误', warn: '警告', info: '信息' }

/** 直接展示服务端保留的全部异常，维护入口不再跳锚点或二次展开。 */
export default function ClientErrorsPage() {
  const logs = useClientLogs()
  return <div className="page" id="page-client-errors">
    <div className="page-head"><div><h2>客户端异常</h2><p>用户端崩溃 / 错误日志 · 保留最新 200 条 · 新在上</p></div><Link className="pill" to={MAINTENANCE_PATH}>返回服务与维护</Link></div>
    {logs.isError && <div className="monitor-query-error" role="alert"><span>客户端异常记录读取失败{logs.data ? '，以下保留上次成功记录' : ''}</span><Pill onClick={() => { void logs.refetch() }}>重试日志</Pill></div>}
    {!logs.data ? <p className="grid-empty">{logs.isError ? '暂无可用的客户端异常记录' : '客户端异常记录加载中…'}</p>
      : logs.data.length === 0 ? <p className="grid-empty">暂无客户端异常上报</p> : <section className="chart-card" aria-label="全部客户端异常">
        <p>全部 {logs.data.length} 条</p>
        <div className="monitor-log-scroll"><table className="log-table">
          <thead><tr><th>时间</th><th>级别</th><th>消息</th><th>页面</th></tr></thead>
          <tbody>{logs.data.map((entry, index) => <tr key={`${entry.ts}-${index}`}><td>{formatDateTime(entry.ts)}</td><td>{LOG_LEVEL_LABELS[entry.level] ?? entry.level}</td><td>{entry.message}</td><td>{entry.page || '—'}</td></tr>)}</tbody>
        </table></div>
      </section>}
  </div>
}
