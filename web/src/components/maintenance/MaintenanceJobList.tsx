/**
 * 维护任务列表（只读账本）：状态筛选 + 游标分页 + 刷新。
 * 纯渲染组件——查询状态由 hooks/use-maintenance-jobs 持有，翻页游标由父组件持有。
 */
import { ListChecks } from 'lucide-react'
import type { MaintenanceJobStatus } from '@/api/generated'
import { EmptyState } from '@/components/ui/empty-state'
import { LoadingHint } from '@/components/ui/loading-hint'
import { Pill } from '@/components/ui/pill'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import type { useMaintenanceJobs } from '@/hooks/use-maintenance-jobs'
import {
  MAINTENANCE_JOB_KIND_LABELS,
  MAINTENANCE_JOB_STATUSES,
  MAINTENANCE_JOB_STATUS_LABELS,
  formatMaintenanceTimestamp,
  isMaintenanceJobStatus,
  maintenanceJobProgress,
} from '@/lib/maintenance-jobs'

type JobListQuery = ReturnType<typeof useMaintenanceJobs>

export function MaintenanceJobList({
  query,
  statusFilter,
  pageIndex,
  selectedJobId,
  onStatusFilterChange,
  onPreviousPage,
  onNextPage,
  onSelect,
  className = '',
}: {
  query: JobListQuery
  statusFilter: MaintenanceJobStatus | 'all'
  pageIndex: number
  selectedJobId: string | null
  onStatusFilterChange: (status: MaintenanceJobStatus | 'all') => void
  onPreviousPage: () => void
  onNextPage: () => void
  onSelect: (jobId: string) => void
  className?: string
}) {
  const refresh = (): void => {
    void query.refetch()
  }
  const page = query.data
  const showPager = page !== undefined && (pageIndex > 0 || page.nextCursor !== null)
  return (
    <section className={className} aria-label="维护任务列表">
      <div className="rank-head">
        <h3><ListChecks aria-hidden="true" /> 维护任务中心</h3>
        <Pill onClick={refresh} disabled={query.isFetching}>{query.isFetching ? '刷新中…' : '刷新'}</Pill>
      </div>
      <p className="rank-note">只读任务账本：查看任务列表与详情、取消未结束任务；本页不创建或执行任务。</p>
      <div className="maintenance-tabs" role="group" aria-label="任务状态筛选">
        <Select
          value={statusFilter}
          onValueChange={(value) => onStatusFilterChange(isMaintenanceJobStatus(value) ? value : 'all')}
        >
          <SelectTrigger aria-label="按状态筛选任务" />
          <SelectContent>
            <SelectItem value="all">全部状态</SelectItem>
            {MAINTENANCE_JOB_STATUSES.map((status) => (
              <SelectItem key={status} value={status}>{MAINTENANCE_JOB_STATUS_LABELS[status]}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      {query.isError && (
        <div className="monitor-query-error" role="alert">
          <span>{page ? '任务列表刷新失败，以下保留上次成功结果。' : '任务列表读取失败，请检查网络或服务端后重试。'}</span>
          <Pill onClick={refresh} disabled={query.isFetching}>重试读取</Pill>
        </div>
      )}
      {!page ? (
        query.isError ? <p className="grid-empty">暂无可用的任务列表。</p> : <LoadingHint>任务列表加载中…</LoadingHint>
      ) : page.items.length === 0 ? (
        <EmptyState compact title="当前筛选下没有维护任务" description="任务由服务端账本记录；创建后刷新列表即可查看。" />
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table className="log-table">
            <thead>
              <tr>
                <th scope="col">创建时间</th>
                <th scope="col">类型</th>
                <th scope="col">状态</th>
                <th scope="col">进度</th>
                <th scope="col">步骤</th>
                <th scope="col">更新时间</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((job) => (
                <tr key={job.id}>
                  <td>{formatMaintenanceTimestamp(job.createdAt)}</td>
                  <td>{MAINTENANCE_JOB_KIND_LABELS[job.kind]}</td>
                  <td>{MAINTENANCE_JOB_STATUS_LABELS[job.status]}</td>
                  <td>{maintenanceJobProgress(job.progress)}%</td>
                  <td>{job.stepCount}</td>
                  <td>{formatMaintenanceTimestamp(job.updatedAt)}</td>
                  <td><Pill active={selectedJobId === job.id} onClick={() => onSelect(job.id)}>查看</Pill></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {showPager && page && (
        <div className="maintenance-tabs" role="group" aria-label="任务列表分页">
          <Pill onClick={onPreviousPage} disabled={pageIndex === 0 || query.isFetching}>上一页</Pill>
          <span className="rank-note">第 {pageIndex + 1} 页</span>
          <Pill onClick={onNextPage} disabled={page.nextCursor === null || query.isFetching}>下一页</Pill>
        </div>
      )}
    </section>
  )
}
