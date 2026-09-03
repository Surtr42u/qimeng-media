/**
 * 客户端异常列表 hook（维护页「用户端崩溃 / 错误日志」排查表消费；
 * 铁律 7：组件不直接调 API）。
 * 数据源 GET /api/v1/client-logs：新→旧，服务端环形缓冲保留最新 200 条。
 */

import { useQuery } from '@tanstack/react-query'
import { getApiV1ClientLogs } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'

export const CLIENT_LOGS_QUERY_KEY = ['api/v1/client-logs'] as const

/** 已上报客户端异常（新→旧；写入方是 lib/client-logs.ts 上报器）。
 *  select 兜底 ?? []：即使服务端异常返回 items:null 也不让页面崩（双保险，
 *  服务端已保证恒返回 []）。 */
export function useClientLogs() {
  return useQuery({
    queryKey: CLIENT_LOGS_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1ClientLogs()),
    select: (page) => page.items ?? [],
  })
}
