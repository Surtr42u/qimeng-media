/**
 * 本机自动同步通道 hooks（维护页「本机同步」卡消费；铁律 7：组件不直接调 API）。
 *
 * 数据源：GET /api/v1/local-sync/status（通道状态 + 同步源内条目 + 最近成功）、
 * POST /api/v1/local-sync/trigger（手动触发一轮扫描，202 异步受理）。
 * 通道语义见生成物 LocalSyncStatus 注释（根目录库名自动入库 + 作者 TXT 导入，
 * 成功源文件移走、失败原地保留，ADR-0030）。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getApiV1LocalSyncStatus, postApiV1LocalSyncTrigger } from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { STATUS_POLL_INTERVAL_MS } from '@/lib/constants'

/** 查询键（无跨 hook 失效需求，按卫生约束第 2 条留在本文件） */
export const LOCAL_SYNC_QUERY_KEY = ['api/v1/local-sync'] as const

/** 触发受理后延迟失效时长：一轮异步扫描需要时间，立即重取拿到的还是旧态（毫秒） */
const TRIGGER_REFRESH_DELAY_MS = 1200

/**
 * 通道状态（SSE 桥不失效本族：轮询 + 手动触发驱动）。
 * refetchInterval 对齐维护页既有轮询先例 useSystemStatus（STATUS_POLL_INTERVAL_MS，
 * 2s）——失败文件靠下轮自动重试自愈，状态卡驻留时需自刷才能看见状态迁移。
 */
export function useLocalSyncStatus() {
  return useQuery({
    queryKey: LOCAL_SYNC_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1LocalSyncStatus()),
    refetchInterval: STATUS_POLL_INTERVAL_MS,
  })
}

/** 手动触发一轮同步扫描：202 即受理成功；409（LOCAL_SYNC_DISABLED）由调用方按错误信息提示 */
export function useTriggerLocalSync() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => unwrapSdkResult(postApiV1LocalSyncTrigger()),
    // 异步扫描非即时完成：延迟失效给服务端留一轮扫描时间，避免重取回旧态
    onSuccess: () => {
      window.setTimeout(() => {
        void qc.invalidateQueries({ queryKey: LOCAL_SYNC_QUERY_KEY })
      }, TRIGGER_REFRESH_DELAY_MS)
    },
  })
}
