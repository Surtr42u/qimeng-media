/**
 * 系统状态轮询 hook（维护页性能监控卡数据源，OBSERVABILITY.md）。
 * refetchInterval = STATUS_POLL_INTERVAL_MS（2s）：曲线图需要连续采样
 * （use-maintenance-monitor 持有有界窗口；差分除以实际成功采样时间差）。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1SystemStatus } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { STATUS_POLL_INTERVAL_MS } from '@/lib/constants'

export function useSystemStatus() {
  return useQuery({
    queryKey: ['api/v1/system/status'],
    queryFn: () => unwrapSdkResult(getApiV1SystemStatus()),
    refetchInterval: STATUS_POLL_INTERVAL_MS,
  })
}
