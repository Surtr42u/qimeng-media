/**
 * 维护任务中心数据钩子（GET /maintenance/jobs、GET /maintenance/jobs/{id}、
 * POST /maintenance/jobs/{id}/cancel 的唯一 Web 消费端；组件禁止直连 SDK）。
 *
 * 语义边界（docs/api/maintenance.md）：本页只读账本 + 取消，不创建/不执行任务；
 * 列表游标分页（nextCursor=null 到底）；详情才返回 steps/artifacts/error。
 * 取消成功后只给账本事实：服务端返回的终态行写入详情缓存，并失效列表族让下一次
 * 列表读取刷新；不本地猜测状态迁移。
 *
 * 响应形状防御（2026-11-07 e2e 修复）：服务端/合成夹具对未知路径可能返回非协议
 * JSON（如 []）。列表按「非对象/缺数组 → 空列表、nextCursor 非字符串 → null」归一，
 * 详情把 steps/artifacts 归一为数组；避免异常形状在渲染期抛错拖垮整页。
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1MaintenanceJobs,
  getApiV1MaintenanceJobsById,
  postApiV1MaintenanceJobsByIdCancel,
  type MaintenanceJob,
  type MaintenanceJobList,
  type MaintenanceJobStatus,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { MAINTENANCE_JOBS_PAGE_SIZE, MAINTENANCE_JOBS_QUERY_KEY } from '@/lib/maintenance-jobs'

/** 归一列表响应：协议是 {items,nextCursor}；异常形状降级为空列表而不是让渲染崩溃。 */
function normalizeJobList(raw: unknown): MaintenanceJobList {
  const source = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>
  const items = Array.isArray(source.items) ? source.items : []
  const nextCursor = typeof source.nextCursor === 'string' && source.nextCursor !== '' ? source.nextCursor : null
  return { items, nextCursor } as MaintenanceJobList
}

/** 归一详情响应：steps/artifacts 缺失或非数组时按空数组处理（只读渲染用）。 */
function normalizeJobDetail(raw: unknown): MaintenanceJob {
  const source = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>
  return {
    ...source,
    steps: Array.isArray(source.steps) ? source.steps : [],
    artifacts: Array.isArray(source.artifacts) ? source.artifacts : [],
  } as MaintenanceJob
}

/** 任务列表：按状态精确筛选 + 游标分页；翻页/换筛选期间保留上一页避免闪空。 */
export function useMaintenanceJobs(params: {
  status?: MaintenanceJobStatus
  cursor?: string
  limit?: number
} = {}) {
  const limit = params.limit ?? MAINTENANCE_JOBS_PAGE_SIZE
  return useQuery({
    queryKey: [...MAINTENANCE_JOBS_QUERY_KEY, 'list', params.status ?? 'all', params.cursor ?? 'first', limit],
    queryFn: async () =>
      normalizeJobList(
        await unwrapSdkResult(
          getApiV1MaintenanceJobs({
            query: {
              ...(params.status ? { status: params.status } : {}),
              ...(params.cursor ? { cursor: params.cursor } : {}),
              limit,
            },
          }),
        ),
      ),
    placeholderData: keepPreviousData,
  })
}

/** 任务详情：未选择任务时不发请求（enabled 闸门）；换任务时保留上一份占位。 */
export function useMaintenanceJob(jobId: string | null) {
  return useQuery({
    queryKey: [...MAINTENANCE_JOBS_QUERY_KEY, 'detail', jobId ?? ''],
    queryFn: async () => {
      if (jobId === null) throw new Error('缺少维护任务 ID')
      return normalizeJobDetail(await unwrapSdkResult(getApiV1MaintenanceJobsById({ path: { id: jobId } })))
    },
    enabled: jobId !== null,
    placeholderData: keepPreviousData,
  })
}

/** 取消任务（协议允许取消任意非终态；终态返回 409 STATE_CONFLICT，由组件提示）。 */
export function useCancelMaintenanceJob() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (jobId: string) =>
      normalizeJobDetail(await unwrapSdkResult(postApiV1MaintenanceJobsByIdCancel({ path: { id: jobId } }))),
    onSuccess: (job) => {
      // 详情用服务端返回的取消后账本行；列表族失效由下一次读取刷新（不本地重排）。
      if (typeof job.id === 'string' && job.id !== '') {
        queryClient.setQueryData([...MAINTENANCE_JOBS_QUERY_KEY, 'detail', job.id], job)
      }
      void queryClient.invalidateQueries({ queryKey: [...MAINTENANCE_JOBS_QUERY_KEY, 'list'] })
    },
  })
}
