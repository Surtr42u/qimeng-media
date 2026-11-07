/**
 * 维护任务中心（维护页区块）：列表/详情/取消的页面状态编排。
 * 数据获取全部在 hooks/use-maintenance-jobs；本组件只持筛选、游标栈与选中项。
 */
import { useState } from 'react'
import type { MaintenanceJobStatus } from '@/api/generated'
import { useMaintenanceJobs } from '@/hooks/use-maintenance-jobs'
import { MaintenanceJobDetail } from './MaintenanceJobDetail'
import { MaintenanceJobList } from './MaintenanceJobList'

export function MaintenanceJobCenter() {
  const [statusFilter, setStatusFilter] = useState<MaintenanceJobStatus | 'all'>('all')
  // 游标栈：首页 undefined；「下一页」压入服务端 nextCursor，「上一页」弹出即回到上一页。
  const [cursorStack, setCursorStack] = useState<Array<string | undefined>>([undefined])
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null)
  const cursor = cursorStack[cursorStack.length - 1]
  const list = useMaintenanceJobs({ status: statusFilter === 'all' ? undefined : statusFilter, cursor })

  const changeStatus = (status: MaintenanceJobStatus | 'all'): void => {
    setStatusFilter(status)
    setCursorStack([undefined])
    setSelectedJobId(null)
  }
  const nextPage = (): void => {
    const nextCursor = list.data?.nextCursor
    if (!nextCursor) return
    setCursorStack((stack) => [...stack, nextCursor])
    setSelectedJobId(null)
  }
  const previousPage = (): void => {
    setCursorStack((stack) => (stack.length > 1 ? stack.slice(0, -1) : stack))
    setSelectedJobId(null)
  }

  return (
    <div className="monitor-grid" aria-label="维护任务中心">
      <MaintenanceJobList
        className="chart-card monitor-span-2"
        query={list}
        statusFilter={statusFilter}
        pageIndex={cursorStack.length - 1}
        selectedJobId={selectedJobId}
        onStatusFilterChange={changeStatus}
        onPreviousPage={previousPage}
        onNextPage={nextPage}
        onSelect={setSelectedJobId}
      />
      <MaintenanceJobDetail
        key={selectedJobId ?? 'no-job'}
        className="chart-card monitor-span-2"
        jobId={selectedJobId}
      />
    </div>
  )
}
