/**
 * 系统状态轮询 hook（维护页性能监控卡数据源，OBSERVABILITY.md）。
 * refetchInterval 2s：曲线图需要连续采样（差分速率在页面组件内本地累积）。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1SystemStatus } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export function useSystemStatus() {
  return useQuery({
    queryKey: ['api/v1/system/status'],
    queryFn: () => unwrapSdkResult(getApiV1SystemStatus()),
    refetchInterval: 2000,
  })
}
