/** 维护页展示模型与有界网络采样；只转换系统快照，不读取 API 或持有业务状态。 */
import type { SystemStatus } from '@/api/generated'
import { STATUS_POLL_INTERVAL_MS } from './constants'
import { formatBytes, formatDurationSeconds } from './format'

/** 两分钟窗口与既有 2s 轮询同源；缺帧按实际时间差计算，最多保留 60 个采样点。 */
const SAMPLE_LIMIT = 60
const SAMPLE_WINDOW_MS = SAMPLE_LIMIT * STATUS_POLL_INTERVAL_MS
const MS_PER_SECOND = 1000
const SECONDS_PER_DAY = 86_400

interface NetworkBaseline { at: number; rx: number; tx: number; uptime: number }
export interface NetworkPoint { at: number; rx: number; tx: number }
export interface NetworkSamples { baseline: NetworkBaseline | null; points: NetworkPoint[] }

export function sampleNetwork(previous: NetworkSamples, status: SystemStatus, at: number): NetworkSamples {
  if (status.netRxBytes == null || status.netTxBytes == null) return previous
  const current = { at, rx: status.netRxBytes, tx: status.netTxBytes, uptime: status.uptimeSeconds ?? 0 }
  const baseline = previous.baseline
  if (baseline && at <= baseline.at) return previous
  // 服务端重启/计数器回退只重建基线，不把旧服务器的尾帧画进本轮曲线。
  if (!baseline || current.rx < baseline.rx || current.tx < baseline.tx || current.uptime < baseline.uptime) {
    return { baseline: current, points: [] }
  }
  const seconds = (at - baseline.at) / MS_PER_SECOND
  const point = { at, rx: (current.rx - baseline.rx) / seconds, tx: (current.tx - baseline.tx) / seconds }
  const points = previous.points.filter(p => p.at > at - SAMPLE_WINDOW_MS).slice(-(SAMPLE_LIMIT - 1))
  return { baseline: current, points: [...points, point] }
}

function percentage(used?: number, total?: number): number | null {
  return used != null && total != null && total > 0 ? Math.min(100, Math.max(0, used / total * 100)) : null
}

export function describeSystem(status?: SystemStatus) {
  const disks = status?.disks ?? []
  const validDisks = disks.filter(d => d.totalBytes != null && d.totalBytes > 0 && d.usedBytes != null)
  // 延续状态接口返回条目的合计，不把挂载目录容量声称为全机物理磁盘总容量。
  const total = validDisks.reduce((sum, d) => sum + d.totalBytes!, 0)
  const used = validDisks.reduce((sum, d) => sum + d.usedBytes!, 0)
  const diskPercent = percentage(validDisks.length ? used : undefined, total)
  const uptime = status?.uptimeSeconds
  const days = uptime == null ? 0 : Math.floor(uptime / SECONDS_PER_DAY)
  return {
    cpu: status?.cpuPercent ?? null,
    memory: percentage(status?.memUsedBytes, status?.memTotalBytes),
    memoryCaption: status?.memUsedBytes != null && status.memTotalBytes
      ? `${formatBytes(status.memUsedBytes)} / ${formatBytes(status.memTotalBytes)}` : '暂未取得内存读数',
    diskPercent,
    diskCaption: diskPercent == null ? '暂未取得存储读数' : `${formatBytes(used)} / ${formatBytes(total)}`,
    diskFree: diskPercent == null ? '—' : formatBytes(Math.max(0, total - used)),
    disks: disks.map((d, index) => ({
      key: `${index}-${d.mount ?? ''}`,
      label: `监控目录 ${index + 1}`,
      mount: d.mount ?? '路径未提供',
      percent: percentage(d.usedBytes, d.totalBytes),
      caption: d.usedBytes != null && d.totalBytes ? `${formatBytes(d.usedBytes)} / ${formatBytes(d.totalBytes)}` : '采集不可用',
    })),
    uptime: uptime == null ? '—' : `${days > 0 ? `${days} 天 ` : ''}${formatDurationSeconds(uptime % SECONDS_PER_DAY)}`,
    version: status?.version || '版本未提供',
  }
}
