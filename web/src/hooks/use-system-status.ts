/**
 * 系统状态 hooks：管理页 CPU/内存/网络监控卡（协议 GET /api/v1/system/status、GET /metrics）。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1SystemStatus } from '@/api/generated'
import { getAuthHeaders, unwrapSdkResult } from '@/lib/api-client'
import { METRICS_PATH, STATUS_POLL_INTERVAL_MS } from '@/lib/constants'

/**
 * 系统状态：默认每 3s 轮询（STATUS_POLL_INTERVAL_MS）。
 * 页面的"暂停/恢复"开关 = 传 refetchInterval: false（暂停）后手动 refetch。
 */
export function useSystemStatus(options: { enabled?: boolean; refetchInterval?: number | false } = {}) {
  return useQuery({
    queryKey: ['system-status'],
    queryFn: () => unwrapSdkResult(getApiV1SystemStatus()),
    refetchInterval: options.refetchInterval ?? STATUS_POLL_INTERVAL_MS,
    enabled: options.enabled ?? true,
  })
}

/** 指标文本（GET /metrics，Prometheus 文本格式；仅管理页"调试"区做纯文本展示，不解析） */
export function useMetrics(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ['metrics'],
    queryFn: async () => {
      const response = await fetch(METRICS_PATH, {
        headers: { ...getAuthHeaders(), Accept: 'text/plain' },
      })
      if (!response.ok) throw new Error(`获取指标失败（HTTP ${response.status}）`)
      return response.text()
    },
    enabled: options.enabled ?? false,
  })
}
