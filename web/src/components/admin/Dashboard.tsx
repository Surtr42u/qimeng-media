/**
 * 监控仪表盘（OBSERVABILITY 约定：system/status 面板，3s 轮询由 useSystemStatus 默认值保证）。
 *
 * 明确不做：/metrics（Prometheus 文本）不做图表解析与展示——那是后端观测体系
 * （Grafana 等）的领域；本页只做 system/status 实时面板，指标文本仅管理"调试"场景。
 *
 * 会话内趋势：每次轮询后按"两帧间隔"换算 Rx/Tx 速率（bytes/s），保存最近
 * SPARKLINE_MAX_POINTS 个采样点（3s 轮询 ≈ 3 分钟窗口）；无持久化历史，刷新即清，
 * 长期覆盖交给未来 Grafana（prometheus 已预留 /metrics）。
 * 采样保护：暂停恢复造成的长间隔会虚高速率——间隔超出 [0.5x, 2x] 标称轮询的帧跳过采样。
 */

import { useEffect, useRef, useState } from 'react'
import { useSystemStatus } from '@/hooks/use-system-status'
import { STATUS_POLL_INTERVAL_MS } from '@/lib/constants'
import { SPARKLINE_MAX_POINTS } from '@/components/charts/sparkline'
import { CpuCard } from '@/components/admin/CpuCard'
import { MemCard } from '@/components/admin/MemCard'
import { DiskCard } from '@/components/admin/DiskCard'
import { NetworkCard } from '@/components/admin/NetworkCard'
import { UptimeCard } from '@/components/admin/UptimeCard'

const TEXT_SECTION_TITLE = '监控仪表盘'
const TEXT_WAITING_FIRST_SAMPLE = '等待首次采样…'
const TEXT_WAITING_HINT = '系统每 3 秒刷新一次；本面板只展示当前状态，不保留历史'

/** 速率采样间隔窗口：下限 0.5x、上限 2x 标称轮询钟——超出视为暂停/切换，帧作废 */
const SAMPLE_INTERVAL_MIN = STATUS_POLL_INTERVAL_MS / 2
const SAMPLE_INTERVAL_MAX = STATUS_POLL_INTERVAL_MS * 2

/** 数据从未采集到（首次采样前服务端返回全 0 零值）的判定：cpuPercent==0 且 uptimeSeconds==0（统计页同口径） */
function hasFirstSample(cpuPercent: number | undefined, uptimeSeconds: number | undefined): boolean {
  return (cpuPercent ?? 0) !== 0 || (uptimeSeconds ?? 0) !== 0
}

/** 上一帧（速率换算用）：模块级引用避免 SSR/多实例问题——本工程为纯 CSR 单实例 */
interface PrevFrame {
  rx: number
  tx: number
  at: number
}

export function Dashboard() {
  const { data } = useSystemStatus()
  const [rxRateHistory, setRxRateHistory] = useState<number[]>([])
  const [txRateHistory, setTxRateHistory] = useState<number[]>([])
  const prevFrameRef = useRef<PrevFrame | null>(null)

  // 数据到达时换算速率并追加（effect 而非渲染期 setState：渲染期写 state 触发 React
  // Compiler 跳过/不一致渲染警告——与 use-sse-events 同精神，effect 保证帧序稳定）
  useEffect(() => {
    if (!data) return
    const prev = prevFrameRef.current
    if (prev) {
      const dt = Date.now() - prev.at
      if (dt >= SAMPLE_INTERVAL_MIN && dt <= SAMPLE_INTERVAL_MAX) {
        const rxRate = Math.max(0, (data.netRxBytes ?? 0) - prev.rx) / (dt / 1000)
        const txRate = Math.max(0, (data.netTxBytes ?? 0) - prev.tx) / (dt / 1000)
        setRxRateHistory((prevHistory) => [...prevHistory, rxRate].slice(-SPARKLINE_MAX_POINTS))
        setTxRateHistory((prevHistory) => [...prevHistory, txRate].slice(-SPARKLINE_MAX_POINTS))
      }
    }
    prevFrameRef.current = {
      rx: data.netRxBytes ?? 0,
      tx: data.netTxBytes ?? 0,
      at: Date.now(),
    }
  }, [data])

  const waiting = !hasFirstSample(data?.cpuPercent, data?.uptimeSeconds)

  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-base font-bold">{TEXT_SECTION_TITLE}</h2>
      {waiting ? (
        <div className="flex flex-col items-center gap-1 rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface-soft)] p-8 text-center">
          <p className="text-sm text-[var(--qm-text-muted)]">{TEXT_WAITING_FIRST_SAMPLE}</p>
          <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_WAITING_HINT}</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4">
            <CpuCard cpuPercent={data?.cpuPercent} perCore={data?.perCore} />
          </div>
          <div className="rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4">
            <MemCard memUsedBytes={data?.memUsedBytes} memTotalBytes={data?.memTotalBytes} />
          </div>
          <div className="rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4">
            <DiskCard disks={data?.disks} />
          </div>
          <div className="rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4">
            <UptimeCard uptimeSeconds={data?.uptimeSeconds} version={data?.version} />
          </div>
          <div className="rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)] p-4 sm:col-span-2">
            <NetworkCard
              netRxBytes={data?.netRxBytes}
              netTxBytes={data?.netTxBytes}
              rxRateHistory={rxRateHistory}
              txRateHistory={txRateHistory}
            />
          </div>
        </div>
      )}
    </section>
  )
}
