/**
 * 维护任务详情（只读账本 + 取消）：类型/状态/进度/时间/错误码、最近步骤与工件。
 * 只渲染服务端返回字段；不展示任务载荷正文，工件仅元数据（无正文）。
 * 取消是账本动作：二次确认后调用 hooks 的 mutation，终态任务不提供入口。
 */
import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { LoadingHint } from '@/components/ui/loading-hint'
import { Pill } from '@/components/ui/pill'
import { ResourceBar } from '@/components/manage/MaintenanceOverview'
import { useCancelMaintenanceJob, useMaintenanceJob } from '@/hooks/use-maintenance-jobs'
import { batchFailureReason } from '@/lib/batch'
import { formatBytes } from '@/lib/format'
import {
  MAINTENANCE_JOB_KIND_LABELS,
  MAINTENANCE_JOB_STATUS_LABELS,
  MAINTENANCE_STEP_STATUS_LABELS,
  canCancelMaintenanceJob,
  formatArtifactDigest,
  formatMaintenanceTimestamp,
  maintenanceJobErrorText,
  maintenanceJobProgress,
  maintenanceStepDetailText,
} from '@/lib/maintenance-jobs'

/** 详情内部滚动表格容器（列宽由浏览器分配，避免监控日志表的固定列宽语义串用）。 */
const scrollStyle = { overflowX: 'auto' as const }

function MetaItem({ label, value }: { label: string; value: string | number }) {
  return <div><span>{label}</span><strong>{value}</strong></div>
}

export function MaintenanceJobDetail({ jobId, className = '' }: { jobId: string | null; className?: string }) {
  const query = useMaintenanceJob(jobId)
  const cancel = useCancelMaintenanceJob()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const job = query.data
  const refresh = (): void => {
    void query.refetch()
  }
  const onCancel = (): void => {
    if (!job || cancel.isPending) return
    cancel.mutate(job.id, {
      onSuccess: () => {
        setConfirmOpen(false)
        toast.success('维护任务已取消')
      },
      onError: (error) => {
        toast.error(`取消失败：${batchFailureReason(error)}`)
        // 409 常见于任务已结束：刷新详情，让页面回到服务端事实而不是继续展示旧状态。
        void query.refetch()
      },
    })
  }
  const errorText = job ? maintenanceJobErrorText(job.error) : ''
  return (
    <section className={className} aria-label="维护任务详情">
      <div className="rank-head">
        <h3>任务详情</h3>
        {job && <Pill onClick={refresh} disabled={query.isFetching}>{query.isFetching ? '刷新中…' : '刷新详情'}</Pill>}
      </div>
      {jobId === null ? (
        <p className="grid-empty">选择左侧任务查看类型、状态、进度、步骤与工件。</p>
      ) : query.isError && !job ? (
        <div className="monitor-query-error" role="alert">
          <span>任务详情读取失败，任务可能已被删除或服务端不可用。</span>
          <Pill onClick={refresh} disabled={query.isFetching}>重试读取</Pill>
        </div>
      ) : !job ? (
        <LoadingHint>任务详情加载中…</LoadingHint>
      ) : (
        <>
          {query.isError && (
            <div className="monitor-query-error" role="alert">
              <span>详情刷新失败，以下为上次成功结果。</span>
              <Pill onClick={refresh} disabled={query.isFetching}>重试读取</Pill>
            </div>
          )}
          <div className="monitor-server-meta">
            <MetaItem label="类型" value={MAINTENANCE_JOB_KIND_LABELS[job.kind]} />
            <MetaItem label="状态" value={MAINTENANCE_JOB_STATUS_LABELS[job.status]} />
            <MetaItem label="创建" value={formatMaintenanceTimestamp(job.createdAt)} />
            <MetaItem label="更新" value={formatMaintenanceTimestamp(job.updatedAt)} />
            <MetaItem label="开始" value={formatMaintenanceTimestamp(job.startedAt)} />
            <MetaItem label="结束" value={formatMaintenanceTimestamp(job.finishedAt)} />
            <MetaItem label="步骤" value={`${job.stepCount} 条`} />
          </div>
          <div className="monitor-row" style={{ marginTop: 16 }}>
            <span>已完成步骤占比</span>
            <strong>{maintenanceJobProgress(job.progress)}%</strong>
          </div>
          <ResourceBar value={maintenanceJobProgress(job.progress)} label="维护任务进度" />
          <p className="rank-note">任务 ID <code>{job.id}</code></p>
          {job.libraryId ? <p className="rank-note">库引用 {job.libraryId}</p> : null}
          {job.rulesVersion ? <p className="rank-note">规则包版本 {job.rulesVersion}</p> : null}
          {errorText !== '' && (
            <div className="monitor-query-error" role="alert" style={{ marginTop: 12 }}>
              <span>任务错误码：{errorText}</span>
            </div>
          )}
          <div className="upload-queue-actions" style={{ marginTop: 12 }}>
            {canCancelMaintenanceJob(job.status)
              ? <Pill onClick={() => setConfirmOpen(true)} disabled={cancel.isPending}>取消任务</Pill>
              : <span className="rank-note">任务已结束，不可再取消。</span>}
          </div>
          <h4 style={{ margin: '18px 0 0' }}>最近步骤</h4>
          {(job.steps ?? []).length === 0 ? (
            <p className="grid-empty">暂无步骤记录。</p>
          ) : (
            <div style={scrollStyle}>
              <table className="log-table">
                <thead>
                  <tr><th scope="col">步骤</th><th scope="col">状态</th><th scope="col">尝试</th><th scope="col">开始</th><th scope="col">结束</th><th scope="col">明细</th></tr>
                </thead>
                <tbody>
                  {(job.steps ?? []).map((step) => (
                    <tr key={step.step}>
                      <td>{step.step}</td>
                      <td>{MAINTENANCE_STEP_STATUS_LABELS[step.status]}</td>
                      <td>{step.attempt}</td>
                      <td>{formatMaintenanceTimestamp(step.startedAt)}</td>
                      <td>{formatMaintenanceTimestamp(step.finishedAt)}</td>
                      <td>{maintenanceStepDetailText(step.detail)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <h4 style={{ margin: '18px 0 0' }}>最近工件</h4>
          {(job.artifacts ?? []).length === 0 ? (
            <p className="grid-empty">暂无工件记录。</p>
          ) : (
            <div style={scrollStyle}>
              <table className="log-table">
                <thead>
                  <tr><th scope="col">类别</th><th scope="col">引用</th><th scope="col">SHA-256</th><th scope="col">大小</th><th scope="col">生成时间</th></tr>
                </thead>
                <tbody>
                  {(job.artifacts ?? []).map((artifact) => (
                    <tr key={`${artifact.kind}-${artifact.uri}`}>
                      <td>{artifact.kind}</td>
                      <td>{artifact.uri}</td>
                      <td title={artifact.sha256}>{formatArtifactDigest(artifact.sha256)}</td>
                      <td>{formatBytes(artifact.bytes)}</td>
                      <td>{formatMaintenanceTimestamp(artifact.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <ConfirmDialog
            open={confirmOpen}
            title="取消维护任务？"
            description={`任务 ${job.id}（${MAINTENANCE_JOB_KIND_LABELS[job.kind]}）将标记为已取消；已记录的步骤与工件索引保留，操作不可恢复。`}
            confirmText={cancel.isPending ? '取消中…' : '确认取消任务'}
            cancelText="返回"
            danger
            busy={cancel.isPending}
            keepOpenOnConfirm
            onConfirm={onCancel}
            onOpenChange={(open) => { if (!open && !cancel.isPending) setConfirmOpen(false) }}
          />
        </>
      )}
    </section>
  )
}
