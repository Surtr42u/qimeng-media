import { Link } from 'react-router'
import { ResourceBar } from '@/components/manage/MaintenanceOverview'
import { Pill } from '@/components/ui/pill'
import { useMaintenanceMonitor } from '@/hooks/use-maintenance-monitor'
import { MAINTENANCE_PATH } from '@/lib/route-keys'

/** 与维护概览共享状态读源，明细进入独立页面，不拉长同排资源卡。 */
export default function StorageDetailsPage() {
  const monitor = useMaintenanceMonitor()
  const { resources } = monitor
  return <div className="page" id="page-storage-details">
    <div className="page-head"><div><h2>存储明细</h2><p>按监控目录展示；同卷目录可能重复计入，非物理磁盘去重容量。</p></div><Link className="pill" to={MAINTENANCE_PATH}>返回服务与维护</Link></div>
    {monitor.isError && <div className="monitor-query-error" role="alert"><span>存储状态读取失败{monitor.data ? '，保留上次成功读数' : ''}</span><Pill onClick={() => { void monitor.refetch() }}>重试存储</Pill></div>}
    {!monitor.data ? <p className="grid-empty">{monitor.isError ? '暂无可用存储读数' : '正在读取存储状态…'}</p> : <>
      <section className="chart-card" aria-label="监控目录容量合计"><h3>监控目录容量合计</h3><ResourceBar value={resources.diskPercent} label="合计存储使用率" /><p>{resources.diskCaption} · 约 {resources.diskFree} 可用</p></section>
      {resources.disks.map(disk => <section className="chart-card monitor-volume" key={disk.key} aria-label={disk.label}>
        <div className="monitor-row"><h3>{disk.label}</h3><span>{disk.percent == null ? '—' : `${disk.percent.toFixed(1)}% 已用`}</span></div>
        <p className="monitor-mount">{disk.mount}</p><ResourceBar value={disk.percent} label={`${disk.label}使用率`} /><p className="monitor-muted">{disk.caption}</p>
      </section>)}
      {resources.disks.length === 0 && <p className="grid-empty">服务端未提供监控目录读数</p>}
    </>}
  </div>
}
