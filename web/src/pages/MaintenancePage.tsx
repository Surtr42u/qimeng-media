import { useState } from 'react'
import { ShieldAlert } from 'lucide-react'
import { MaintenanceOverview, MAINTENANCE_LOGS_ID } from '@/components/manage/MaintenanceOverview'
import { Pill } from '@/components/ui/pill'
import { useClientLogs } from '@/hooks/use-client-logs'
import { useMaintenanceMonitor } from '@/hooks/use-maintenance-monitor'
import { useTrash } from '@/hooks/use-trash'
import { formatDateTime } from '@/lib/format'

/** 折叠态仍显示最近 5 条；服务端环形缓冲 200 条，避免日志淹没操作区。 */
const CLIENT_LOGS_PREVIEW_COUNT = 5
const LOG_LEVEL_LABELS: Record<string, string> = { error: '错误', warn: '警告', info: '信息' }

/** 紧凑概览与完整维护操作共存；原有接口/路由/本机同步行为保持，快照调度卡仍不展示。 */
export default function MaintenancePage() {
  const monitor = useMaintenanceMonitor()
  const trash = useTrash()
  const logs = useClientLogs()
  const clientLogs = logs.data ?? []
  const [logsExpanded, setLogsExpanded] = useState(false)
  const trashLabel = trash.isError ? '条目数暂不可用' : trash.data ? `${trash.data.length} 条待处理` : '正在读取…'
  const logsLabel = logs.isError ? '更新失败，查看详情' : logs.data ? `保留 ${clientLogs.length} 条上报` : '正在读取…'

  return (
    <div className="page" id="page-maintenance">
      {monitor.data?.devMode === true && (
        <div className="dev-mode-banner" role="alert"><ShieldAlert aria-hidden="true" /><span>开发模式未关：此服务器免密登录已开启，请勿暴露到公网/隧道</span></div>
      )}
      <div className="page-head monitor-page-head">
        <div><h2>服务与维护</h2><p>运行概况、资源使用与常用维护</p></div>
        <span className="monitor-update" role="status">{monitor.stateLabel} · 每 2 秒</span>
      </div>
      {monitor.isError && <div className="monitor-query-error" role="alert">
        <span>{monitor.data ? '监控更新失败，以下保留上次成功读数。' : '暂时无法读取监控状态，维护工具仍可使用。'}</span>
        <Pill onClick={() => { void monitor.refetch() }} disabled={monitor.isFetching}>重试监控</Pill>
      </div>}
      <MaintenanceOverview monitor={monitor} trashLabel={trashLabel} logsLabel={logsLabel} />
      <section className="chart-card monitor-detail-section" id={MAINTENANCE_LOGS_ID} aria-label="客户端异常详情">
        <h3>用户端崩溃 / 错误日志</h3>
        <p>客户端异常上报 · 服务端保留最新 200 条 · 新在上</p>
        {logs.isError && <div className="monitor-query-error" role="alert"><span>客户端异常记录读取失败{logs.data ? '，以下保留上次成功记录' : ''}</span><Pill onClick={() => { void logs.refetch() }}>重试日志</Pill></div>}
        {!logs.data ? <p className="grid-empty">{logs.isError ? '暂无可用的客户端异常记录' : '客户端异常记录加载中…'}</p>
            : clientLogs.length === 0 ? <p className="grid-empty">暂无客户端异常上报</p> : <>
              <div className="monitor-log-scroll">
                <table className="log-table">
                  <thead><tr><th>时间</th><th>级别</th><th>消息</th><th>页面</th></tr></thead>
                  <tbody>{(logsExpanded ? clientLogs : clientLogs.slice(0, CLIENT_LOGS_PREVIEW_COUNT)).map((entry, index) => (
                    <tr key={`${entry.ts}-${index}`}><td>{formatDateTime(entry.ts)}</td><td>{LOG_LEVEL_LABELS[entry.level] ?? entry.level}</td><td>{entry.message}</td><td>{entry.page || '—'}</td></tr>
                  ))}</tbody>
                </table>
              </div>
              {clientLogs.length > CLIENT_LOGS_PREVIEW_COUNT && <Pill onClick={() => setLogsExpanded(value => !value)}>
                {logsExpanded ? `收起（只看最近 ${CLIENT_LOGS_PREVIEW_COUNT} 条）` : `查看全部 ${clientLogs.length} 条`}
              </Pill>}
            </>}
      </section>
    </div>
  )
}
