/**
 * 网络卡：Rx/Tx 累计（formatBytes）+ 会话内历史趋势小图（自绘 Sparkline）。
 *
 * 【断点说明】趋势数据仅保存在本会话内存（刷新即清），长期历史由未来
 * Grafana/Prometheus 覆盖（/metrics 端点已暴露，本页不做解析，见 AdminPage 注释）。
 */

import { ArrowDown, ArrowUp } from 'lucide-react'
import { formatBytes } from '@/lib/format'
import { Sparkline } from '@/components/charts/sparkline'

/** 下行速率趋势图小注（窗口=采样保留上限，见 Dashboard） */
const TEXT_RX_TREND_LABEL = '下行速率（会话内）'
const TEXT_TX_TREND_LABEL = '上行速率（会话内）'

export interface NetworkCardProps {
  netRxBytes?: number
  netTxBytes?: number
  /** 会话内下行速率采样序列（bytes/s，最近一次在末尾） */
  rxRateHistory: number[]
  /** 会话内上行速率采样序列（bytes/s，最近一次在末尾） */
  txRateHistory: number[]
}

export function NetworkCard({ netRxBytes, netTxBytes, rxRateHistory, txRateHistory }: NetworkCardProps) {
  return (
    <div className="flex flex-col gap-3">
      <div className="grid grid-cols-2 gap-2">
        <div className="flex items-center gap-2 rounded-lg bg-[var(--qm-chip-bg)] px-3 py-2">
          <ArrowDown className="size-4 shrink-0 text-[var(--qm-text-muted)]" aria-hidden />
          <div className="min-w-0">
            <p className="text-[10px] text-[var(--qm-text-muted)]">累计下行</p>
            <p className="truncate font-mono text-sm font-semibold">{formatBytes(netRxBytes)}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 rounded-lg bg-[var(--qm-chip-bg)] px-3 py-2">
          <ArrowUp className="size-4 shrink-0 text-[var(--qm-text-muted)]" aria-hidden />
          <div className="min-w-0">
            <p className="text-[10px] text-[var(--qm-text-muted)]">累计上行</p>
            <p className="truncate font-mono text-sm font-semibold">{formatBytes(netTxBytes)}</p>
          </div>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Sparkline data={rxRateHistory} height={40} label={TEXT_RX_TREND_LABEL} />
        <Sparkline data={txRateHistory} height={40} label={TEXT_TX_TREND_LABEL} />
      </div>
    </div>
  )
}
