import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { FolderMonitorIcon, TrashIcon } from '@/components/shell/icons'
import { useClientLogs } from '@/hooks/use-client-logs'
import { useSystemStatus } from '@/hooks/use-system-status'
import { useTrash } from '@/hooks/use-trash'
import { STATUS_POLL_INTERVAL_MS } from '@/lib/constants'
import { formatBytes, formatDateTime } from '@/lib/format'
import { Line, LineChart, ResponsiveContainer, Tooltip, YAxis } from 'recharts'
import { TIP_STYLE, TrendLegend } from '@/components/data/chart-shared'

/** 曲线采样：最近 2 分钟、每 2 秒一点（60 点）；与轮询周期（2s）耦合 */
const RATE_WINDOW = 60
/** 速率差分除数（秒）：两帧字节差 ÷ 轮询间隔秒数 = B/s——与 STATUS_POLL_INTERVAL_MS
 *  同源派生，轮询周期若调整，差分口径自动跟随，纵轴仍是 B/s */
const RATE_DIVISOR_S = STATUS_POLL_INTERVAL_MS / 1000
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
 * （本地 60 点环形采样）；「客户端异常」表 = GET /client-logs（上报器写入，
 * 服务端环形缓冲最新 200 条，2026-09-04 接真）。
 */

/** 客户端异常级别中文标签（协议 level 枚举 error/warn/info） */
const LOG_LEVEL_LABELS: Record<string, string> = {
  error: '错误',
  warn: '警告',
  info: '信息',
}
export default function MaintenancePage() {
  const navigate = useNavigate()
  const { data: status } = useSystemStatus()
  // 回收站入口卡计数用真实数据
  const { data: trashItems = [] } = useTrash()
  // 客户端异常排查表（新→旧，环形缓冲最新 200 条）
  const { data: clientLogs = [] } = useClientLogs()

  // 速率环形缓冲：每帧拿 netRx/netTx 与上一帧累计值差分 ÷ 轮询间隔秒 → B/s
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
        rx: Math.max(0, cur.rx - prev.rx) / RATE_DIVISOR_S,
        tx: Math.max(0, cur.tx - prev.tx) / RATE_DIVISOR_S,
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

  // 网络负载图已换 recharts（ADR-0016），悬停提示/参考线/高亮点库内置

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
        <TrendLegend
          items={[
            { label: '下行', color: 'var(--qm-primary)' },
            { label: '上行', color: 'var(--trend-line-sub)' },
          ]}
        />
        {/* recharts 实现（ADR-0016）：悬停提示/参考线/高亮点库内置；
            isAnimationActive=false——2s 滚动刷新重放动画会持续闪烁 */}
        <ResponsiveContainer width="100%" height={200}>
          <LineChart data={rates.map((r) => ({ 下行: r.rx, 上行: r.tx }))} margin={{ top: 6, right: 8, left: 8, bottom: 0 }}>
            <YAxis hide />
            <Tooltip {...TIP_STYLE} formatter={(value) => `${formatBytes(Number(value))}/s`} />
            <Line
              type="monotone"
              dataKey="下行"
              name="下行"
              stroke="var(--qm-primary)"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, fill: 'var(--qm-primary)' }}
              isAnimationActive={false}
            />
            <Line
              type="monotone"
              dataKey="上行"
              name="上行"
              stroke="var(--trend-line-sub)"
              strokeWidth={2}
              strokeDasharray="4 4"
              dot={false}
              activeDot={{ r: 4, fill: 'var(--trend-line-sub)' }}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
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
        <p>客户端异常上报 · 服务端保留最新 200 条 · 新在上</p>
        {clientLogs.length === 0 ? (
          <p className="grid-empty">暂无客户端异常上报（环形缓冲为空——没出错就是好消息）</p>
        ) : (
          <table className="log-table">
            <thead>
              <tr>
                <th style={{ width: 110 }}>时间</th>
                <th style={{ width: 60 }}>级别</th>
                <th>消息</th>
                <th style={{ width: 180 }}>页面</th>
              </tr>
            </thead>
            <tbody>
              {clientLogs.map((e, i) => (
                <tr key={`${e.ts}-${i}`}>
                  <td>{formatDateTime(e.ts)}</td>
                  <td>{LOG_LEVEL_LABELS[e.level] ?? e.level}</td>
                  <td>{e.message}</td>
                  <td>{e.page || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
