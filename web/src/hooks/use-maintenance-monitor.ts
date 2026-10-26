/** 维护概览的数据转换/网络窗口归本 hook，渲染组件不计算采样或资源口径。 */
import { useEffect, useMemo, useState } from 'react'
import { useSystemStatus } from './use-system-status'
import { describeSystem, sampleNetwork, type NetworkSamples } from '@/lib/system-monitor'
import { formatBytes } from '@/lib/format'

export function useMaintenanceMonitor() {
  const query = useSystemStatus()
  const { data: status, dataUpdatedAt } = query
  const [samples, setSamples] = useState<NetworkSamples>({ baseline: null, points: [] })
  useEffect(() => {
    if (status) setSamples(previous => sampleNetwork(previous, status, dataUpdatedAt))
  }, [status, dataUpdatedAt])
  const resources = useMemo(() => describeSystem(status), [status])
  const traffic = useMemo(() => (['rx', 'tx'] as const).map(direction => {
    const values = samples.points.map(p => p[direction])
    return {
      direction,
      label: direction === 'rx' ? '接收 / 下行' : '发送 / 上行',
      value: values.length ? formatBytes(values[values.length - 1]) : null,
      peak: values.length ? `${formatBytes(Math.max(...values))}/s` : '—',
      cumulative: status?.[direction === 'rx' ? 'netRxBytes' : 'netTxBytes'],
    }
  }), [samples.points, status])
  const stateLabel = query.isError
    ? (status ? '更新失败 · 保留上次读数' : '监控暂不可用')
    : status ? '正在自动更新' : '正在读取状态'
  return { ...query, resources, traffic, points: samples.points, stateLabel }
}
