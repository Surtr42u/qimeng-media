/**
 * 维护任务中心数据钩子（GET /maintenance/jobs、GET /maintenance/jobs/{id}、
 * POST /maintenance/jobs/{id}/cancel 的唯一 Web 消费端；组件禁止直连 SDK）。
 *
 * 语义边界（docs/api/maintenance.md）：本页只读账本 + 取消，不创建/不执行任务；
 * 列表游标分页（nextCursor=null 到底）；详情才返回 steps/artifacts/error。
 * 取消成功后只给账本事实：服务端返回的终态行写入详情缓存，并失效列表族让下一次
 * 列表读取刷新；不本地猜测状态迁移。
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1MaintenanceJobs,
  getApiV1MaintenanceJobsById,
  postApiV1MaintenanceJobsByIdCancel,
  type MaintenanceJobStatus,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { MAINTENANCE_JOBS_PAGE_SIZE, MAINTENANCE_JOBS_QUERY_KEY } from '@/lib/maintenance-jobs'

/** 任务列表：按状态精确筛选 + 游标分页；翻页/换筛选期间保留上一页避免闪空。 */
export function useMaintenanceJobs(params: {
  status?: MaintenanceJobStatus
  cursor?: string
  limit?: number
} = {}) {
  const limit = params.limit ?? MAINTENANCE_JOBS_PAGE_SIZE
  return useQuery({
    queryKey: [...MAINTENANCE_JOBS_QUERY_KEY, 'list', params.status ?? 'all', params.cursor ?? 'first', limit],
    queryFn: () =>
      unwrapSdkResult(
        getApiV1MaintenanceJobs({
          query: {
            ...(params.status ? { status: params.status } : {}),
            ...(params.cursor ? { cursor: params.cursor } : {}),
            limit,
          },
        }),
      ),
    placeholderData: keepPreviousData,
  })
}

/** 任务详情：未选择任务时不发请求（enabled 闸门）；换任务时保留上一份占位。 */
export function useMaintenanceJob(jobId: string | null) {
  return useQuery({
    queryKey: [...MAINTENANCE_JOBS_QUERY_KEY, 'detail', jobId ?? ''],
    queryFn: () => {
      if (jobId === null) throw new Error('缺少维护任务 ID')
      return unwrapSdkResult(getApiV1MaintenanceJobsById({ path: { id: jobId } }))
    },
    enabled: jobId !== null,
    placeholderData: keepPreviousData,
  })
}

/** 取消任务（协议允许取消任意非终态；终态返回 409 STATE_CONFLICT，由组件提示）。 */
export function useCancelMaintenanceJob() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (jobId: string) =>
      unwrapSdkResult(postApiV1MaintenanceJobsByIdCancel({ path: { id: jobId } })),
    onSuccess: (job) => {
      // 详情用服务端返回的取消后账本行；列表族失效由下一次读取刷新（不本地重排）。
      queryClient.setQueryData([...MAINTENANCE_JOBS_QUERY_KEY, 'detail', job.id], job)
      void queryClient.invalidateQueries({ queryKey: [...MAINTENANCE_JOBS_QUERY_KEY, 'list'] })
    },
  })
}
