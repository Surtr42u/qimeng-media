import { ShieldAlert } from 'lucide-react'
import { MaintenanceOverview } from '@/components/manage/MaintenanceOverview'
import { MaintenanceJobCenter } from '@/components/maintenance/MaintenanceJobCenter'
import { Pill } from '@/components/ui/pill'
import { useClientLogs } from '@/hooks/use-client-logs'
import { useMaintenanceMonitor } from '@/hooks/use-maintenance-monitor'
import { useTrash } from '@/hooks/use-trash'

/** 紧凑概览与完整维护操作共存；原有接口/路由/本机同步行为保持，快照调度卡仍不展示。 */
export default function MaintenancePage() {
  const monitor = useMaintenanceMonitor()
  const trash = useTrash()
  const logs = useClientLogs()
  const clientLogs = logs.data ?? []
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
      <MaintenanceJobCenter />
    </div>
  )
}
