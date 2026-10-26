import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Activity, ArrowDown, ArrowUp, ChevronRight, Cpu, FileWarning, FolderSearch, HardDrive, MemoryStick, RefreshCw, Server, Trash2 } from 'lucide-react'
import { Area, AreaChart, Line, LineChart, ResponsiveContainer, Tooltip, YAxis } from 'recharts'
import { TrendLegend, TIP_STYLE } from '@/components/data/chart-shared'
import type { useMaintenanceMonitor } from '@/hooks/use-maintenance-monitor'
import { formatBytes } from '@/lib/format'
import { MAINTENANCE_FILES_PATH } from '@/lib/route-keys'

type Monitor = ReturnType<typeof useMaintenanceMonitor>
/** 锚点只跳到仍常驻的完整操作区，不卸载同步查询或改变操作行为。 */
export const MAINTENANCE_SYNC_ID = 'maintenance-sync'
export const MAINTENANCE_LOGS_ID = 'maintenance-logs'
const GAUGE_CIRC = 2 * Math.PI * 44

function CardHeading({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return <div className="monitor-card-head">{icon}<h3>{children}</h3></div>
}

function ResourceBar({ value, label, secondary = false }: { value: number | null; label: string; secondary?: boolean }) {
  return <div className={`monitor-bar${secondary ? ' monitor-bar--secondary' : ''}`} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={value ?? undefined} aria-valuetext={value == null ? '暂无读数' : `${value.toFixed(1)}%`}>
    <i style={{ width: `${value ?? 0}%` }} />
  </div>
}

function TrafficCard({ traffic, points }: { traffic: Monitor['traffic'][number]; points: Monitor['points'] }) {
  const receive = traffic.direction === 'rx'
  const color = receive ? 'var(--qm-chart-1)' : 'var(--qm-chart-2)'
  return <section className={`chart-card monitor-traffic monitor-traffic--${traffic.direction}`} aria-label={traffic.label}>
    <CardHeading icon={receive ? <ArrowDown aria-hidden="true" /> : <ArrowUp aria-hidden="true" />}>{traffic.label}</CardHeading>
    <div className="monitor-value">{traffic.value ?? '采样中…'}{traffic.value && <small>/s</small>}</div>
    <p>窗口峰值 {traffic.peak}</p>
    <div className="monitor-sparkline" aria-hidden="true">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={points} margin={{ top: 8, right: 0, left: 0, bottom: 0 }}>
          <Area type="monotone" dataKey={traffic.direction} stroke={color} fill={color} fillOpacity={0.1} strokeWidth={2} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
    <p className="monitor-footnote">累计 {traffic.cumulative == null ? '—' : formatBytes(traffic.cumulative)}</p>
  </section>
}

export function MaintenanceOverview({ monitor, trashLabel, syncLabel, logsLabel }: {
  monitor: Monitor; trashLabel: string; syncLabel: string; logsLabel: string
}) {
  const { resources, data: status } = monitor
  return <>
    <div className="monitor-grid">
      <section className="chart-card monitor-server monitor-span-2">
        <CardHeading icon={<Server aria-hidden="true" />}>服务端运行时长</CardHeading>
        <div className="monitor-value monitor-uptime">{resources.uptime}</div>
        <p>当前连接的服务器 · {resources.version}</p>
        <div className="monitor-server-meta">
          <div><span>状态更新</span><strong className={monitor.isError ? 'monitor-update--error' : ''}>{monitor.stateLabel}</strong></div>
          <div><span>采样范围</span><strong>系统资源 / 全机网卡聚合</strong></div>
        </div>
      </section>
      {monitor.traffic.map(traffic => <TrafficCard key={traffic.direction} traffic={traffic} points={monitor.points} />)}
      <section className="chart-card monitor-cpu">
        <CardHeading icon={<Cpu aria-hidden="true" />}>处理器</CardHeading>
        <div className="monitor-resource">
          <div className="monitor-gauge">
            <svg viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="44" className="monitor-gauge-track" /><circle cx="50" cy="50" r="44" className="monitor-gauge-fill" strokeDasharray={`${Math.min(100, Math.max(0, resources.cpu ?? 0)) * GAUGE_CIRC / 100} ${GAUGE_CIRC}`} /></svg>
            <strong>{resources.cpu == null ? '—' : `${resources.cpu.toFixed(0)}%`}</strong>
          </div>
          <div><p>CPU 使用率</p><p className="monitor-muted">整体负载</p></div>
        </div>
      </section>
      <section className="chart-card monitor-memory">
        <CardHeading icon={<MemoryStick aria-hidden="true" />}>系统内存</CardHeading>
        <div className="monitor-value">{resources.memory == null ? '—' : <>{resources.memory.toFixed(1)}<small>%</small></>}</div>
        <ResourceBar value={resources.memory} label="系统内存使用率" secondary />
        <p>{resources.memoryCaption}</p>
      </section>
      <section className="chart-card monitor-storage monitor-span-2">
        <CardHeading icon={<HardDrive aria-hidden="true" />}>存储空间</CardHeading>
        <div className="monitor-row"><span>监控目录容量合计</span><strong>{resources.diskPercent == null ? '—' : `${resources.diskPercent.toFixed(1)}% 已用`}</strong></div>
        <ResourceBar value={resources.diskPercent} label="存储使用率" />
        <p>{resources.diskCaption} · 约 {resources.diskFree} 可用</p>
        <details className="monitor-disks">
          <summary>查看存储明细 <span>{resources.disks.length} 项</span></summary>
          <p className="monitor-muted">按监控目录合计，同卷目录可能重复计入，非物理磁盘去重容量。</p>
          {resources.disks.map(disk => <div className="monitor-volume" key={disk.key}>
            <div className="monitor-row"><strong>{disk.label}</strong><span>{disk.percent == null ? '—' : `${disk.percent.toFixed(1)}%`}</span></div>
            <p className="monitor-mount">{disk.mount}</p>
            <ResourceBar value={disk.percent} label={`${disk.label}使用率`} />
            <p className="monitor-muted">{disk.caption}</p>
          </div>)}
          {!status && <p className="monitor-muted">等待读取存储状态</p>}
        </details>
      </section>
      <section className="chart-card monitor-span-2">
        <CardHeading icon={<FolderSearch aria-hidden="true" />}>常用维护</CardHeading>
        <div className="monitor-shortcuts">
          <Link className="monitor-shortcut" to={MAINTENANCE_FILES_PATH}><FolderSearch aria-hidden="true" /><span><strong>文件管理</strong><small>目录、注册与扫描</small></span><ChevronRight aria-hidden="true" /></Link>
          <Link className="monitor-shortcut" to="/app/maintenance/trash"><Trash2 aria-hidden="true" /><span><strong>回收站</strong><small>{trashLabel}</small></span><ChevronRight aria-hidden="true" /></Link>
        </div>
      </section>
      <section className="chart-card monitor-span-2">
        <CardHeading icon={<Activity aria-hidden="true" />}>同步与异常</CardHeading>
        <div className="monitor-shortcuts">
          <a className="monitor-shortcut" href={`#${MAINTENANCE_SYNC_ID}`}><RefreshCw aria-hidden="true" /><span><strong>本机同步</strong><small>{syncLabel}</small></span><ChevronRight aria-hidden="true" /></a>
          <a className="monitor-shortcut" href={`#${MAINTENANCE_LOGS_ID}`}><FileWarning aria-hidden="true" /><span><strong>客户端异常</strong><small>{logsLabel}</small></span><ChevronRight aria-hidden="true" /></a>
        </div>
      </section>
    </div>
    <details className="chart-card monitor-network-details">
      <summary><span>网络负载 · 实时</span><span className="monitor-muted">最近 2 分钟 · 双向曲线</span></summary>
      <p className="monitor-muted">全机网卡聚合 · 2s 轮询 · 最近 2 分钟有效采样{monitor.points.length < 2 ? '（采样中…）' : ''}</p>
      <TrendLegend items={[{ label: '接收 / 下行', color: 'var(--qm-chart-1)' }, { label: '发送 / 上行', color: 'var(--qm-chart-2)' }]} />
      <ResponsiveContainer width="100%" height={200}>
        <LineChart data={monitor.points} margin={{ top: 6, right: 8, left: 8, bottom: 0 }}>
          <YAxis hide />
          <Tooltip {...TIP_STYLE} labelFormatter={() => ''} formatter={value => `${formatBytes(Number(value))}/s`} />
          <Line type="monotone" dataKey="rx" name="接收 / 下行" stroke="var(--qm-chart-1)" strokeWidth={2} dot={false} activeDot={{ r: 4 }} isAnimationActive={false} />
          <Line type="monotone" dataKey="tx" name="发送 / 上行" stroke="var(--qm-chart-2)" strokeWidth={2} strokeDasharray="4 4" dot={false} activeDot={{ r: 4 }} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </details>
  </>
}
