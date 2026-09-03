import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { FolderMonitorIcon, TrashIcon } from '@/components/shell/icons'
import { useSystemStatus } from '@/hooks/use-system-status'
import { useTrash } from '@/hooks/use-trash'
import { formatBytes } from '@/lib/format'

/** 曲线采样：最近 2 分钟、每 2 秒一点（60 点）；与轮询周期（2s）耦合 */
const RATE_WINDOW = 60
/** 原型 gauge 视口：圆心 50/50、半径 44 → 周长 2πr（dash 基数） */
const GAUGE_CIRC = 2 * Math.PI * 44

interface RatePoint {
  rx: number
  tx: number
}

/**
 * 维护页（原型 #page-maintenance 移植，2026-09-03 性能监控接真）。
 * 监控数据 = GET /system/status（2s 轮询）：4 圆环卡（CPU/内存/系统盘/存储合计）、
 * 4 指标卡（上下行速率=轮询差分本地计算、存储合计、运行时长）、网络负载曲线
 * （本地 60 点环形采样）；「客户端异常」无上报数据源（客户端错误上报通道未建），
 * 显示空态说明。
 */
export default function MaintenancePage() {
  const navigate = useNavigate()
  const { data: status } = useSystemStatus()
  // 回收站入口卡计数用真实数据
  const { data: trashItems = [] } = useTrash()

  // 速率环形缓冲：每帧拿 netRx/netTx 与上一帧累计值差分 → B/s（/2s=间隔）
  const [rates, setRates] = useState<RatePoint[]>([])
  const accumRef = useRef<{ rx: number; tx: number } | null>(null)
  useEffect(() => {
    if (!status || !status.netRxBytes || !status.netTxBytes) return
    const cur = { rx: status.netRxBytes, tx: status.netTxBytes }
    const prev = accumRef.current
    accumRef.current = cur
    if (!prev) return // 首帧只记基线，下一帧起差分
    setRates((r) => [
      ...r.slice(-(RATE_WINDOW - 1)),
      {
        rx: Math.max(0, cur.rx - prev.rx) / 2,
        tx: Math.max(0, cur.tx - prev.tx) / 2,
      },
    ])
  }, [status])

  const memPct = useMemo(() => {
    if (!status?.memTotalBytes || !status.memUsedBytes) return 0
    return (status.memUsedBytes / status.memTotalBytes) * 100
  }, [status])

  const diskTotal = useMemo(
    () => (status?.disks ?? []).reduce((a, d) => a + (d.totalBytes ?? 0), 0),
    [status],
  )
  const diskUsed = useMemo(
    () => (status?.disks ?? []).reduce((a, d) => a + (d.usedBytes ?? 0), 0),
    [status],
  )
  const diskPct = diskTotal > 0 ? (diskUsed / diskTotal) * 100 : 0
  const sysDisk = status?.disks?.[0]
  const sysDiskPct =
    sysDisk && sysDisk.totalBytes ? ((sysDisk.usedBytes ?? 0) / sysDisk.totalBytes) * 100 : 0

  /** 4 圆环卡的百分比与副标题（顺序固定：CPU/内存/系统盘/存储合计） */
  const gauges = useMemo(() => {
    const pcts = [status?.cpuPercent ?? 0, memPct, sysDiskPct, diskPct]
    const subs = [
      `使用率 ${pcts[0].toFixed(0)}%`,
      status ? `${formatBytes(status.memUsedBytes ?? 0)} / ${formatBytes(status.memTotalBytes ?? 0)}` : '—',
      sysDisk ? `${formatBytes(sysDisk.usedBytes ?? 0)} / ${formatBytes(sysDisk.totalBytes ?? 0)} · ${sysDisk.mount}` : '—',
      `${formatBytes(diskUsed)} / ${formatBytes(diskTotal)}`,
    ]
    return [
      { label: 'CPU', arc: 'arc-1', value: pcts[0], sub: subs[0] },
      { label: '内存', arc: 'arc-2', value: pcts[1], sub: subs[1] },
      { label: '系统盘', arc: 'arc-1', value: pcts[2], sub: subs[2] },
      { label: '存储合计', arc: 'arc-2', value: pcts[3], sub: subs[3] },
    ]
  }, [status, memPct, sysDisk, sysDiskPct, diskUsed, diskTotal, diskPct])

  /** 指标卡（速率=窗口内最后一帧；峰值=窗口最大；运行时长=uptime） */
  const metrics = useMemo(() => {
    const rxs = rates.map((r) => r.rx)
    const txs = rates.map((r) => r.tx)
    const up = status?.uptimeSeconds ?? 0
    const days = Math.floor(up / 86400)
    const hours = Math.floor((up % 86400) / 3600)
    return [
      { label: '下行', value: rates.length ? `${formatBytes(rxs[rxs.length - 1])}/s` : '采样中…', sub: `窗口峰值 ${formatBytes(Math.max(0, ...rxs))}/s` },
      { label: '上行', value: rates.length ? `${formatBytes(txs[txs.length - 1])}/s` : '采样中…', sub: `窗口峰值 ${formatBytes(Math.max(0, ...txs))}/s` },
      { label: '存储', value: formatBytes(diskUsed), sub: `${formatBytes(diskTotal)} 合计 · ${diskPct.toFixed(1)}% 已用` },
      { label: '运行时长', value: `${days} 天`, sub: `${days} 天 ${hours} 时` },
    ]
  }, [rates, status?.uptimeSeconds, diskUsed, diskTotal, diskPct])

  /** 折扣线坐标：600×200 视口，按窗口峰值归一化（峰值 0 时保持底线） */
  const polyline = (key: 'rx' | 'tx') => {
    const arr = rates.map((r) => r[key])
    const max = Math.max(1, ...arr)
    const span = Math.max(1, arr.length - 1)
    return arr
      .map((v, i) => `${((i * 600) / span).toFixed(0)},${(190 - (v / max) * 170).toFixed(0)}`)
      .join(' ')
  }

  return (
    <div className="page" id="page-maintenance">
      <div className="page-head">
        <h2>性能监控</h2>
        <p>服务器硬件实时状态 · 每 2 秒自动刷新</p>
      </div>
      <div className="gauge-grid">
        {gauges.map((g) => (
          <div key={g.label} className="gauge-card">
            <svg className="gauge" viewBox="0 0 100 100" aria-hidden="true">
              <circle cx="50" cy="50" r="44" className="ring" />
              <circle
                cx="50"
                cy="50"
                r="44"
                className={`${g.arc} gauge-arc`}
                strokeDasharray={`${(Math.min(100, Math.max(0, g.value)) * (GAUGE_CIRC / 100)).toFixed(1)} ${GAUGE_CIRC.toFixed(1)}`}
              />
            </svg>
            <p className="gauge-label">{g.label}</p>
            <p className="gauge-sub">{g.sub}</p>
          </div>
        ))}
      </div>
      <div className="metric-grid">
        {metrics.map((m) => (
          <div key={m.label} className="metric-card">
            <p className="m-label">{m.label}</p>
            <p className="m-value">{m.value}</p>
            <p className="m-sub">{m.sub}</p>
          </div>
        ))}
      </div>
      <div className="chart-card">
        <h3>网络负载 · 实时</h3>
        <p>浏览流量上下行曲线 · 2s 采样 · 最近 2 分钟{rates.length < 2 ? '（采样中…）' : ''}</p>
        <svg className="trend-svg" viewBox="0 0 600 200" preserveAspectRatio="none" aria-label="网络负载">
          <polyline points={polyline('rx')} className="line-a" />
          <polyline points={polyline('tx')} className="line-b" />
        </svg>
      </div>
      <div className="page-head">
        <h2>维护工具</h2>
        <p>文件管理与客户端异常排查</p>
      </div>
      <div className="entry-grid">
        <div className="entry-card entry-card--link" onClick={() => navigate('/app/maintenance/files')}>
          <div className="entry-head">
            <FolderMonitorIcon />
            <h3>文件管理</h3>
          </div>
          <p>管理文件库：注册/删除媒体目录、触发扫描、浏览目录结构</p>
        </div>
        <div className="entry-card entry-card--link" onClick={() => navigate('/app/maintenance/trash')}>
          <div className="entry-head">
            <TrashIcon />
            <h3>回收站</h3>
          </div>
          <p className="entry-count">
            <b>{trashItems.length}</b> 条待处理
          </p>
          <p className="entry-sub">恢复或彻底清除都在回收站页</p>
        </div>
      </div>
      <div className="chart-card">
        <h3>用户端崩溃 / 错误日志</h3>
        <p className="grid-empty">暂无客户端异常上报（客户端错误上报通道未建，后续接入后此表生效）</p>
      </div>
    </div>
  )
}
