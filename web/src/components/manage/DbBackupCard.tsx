import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Switch } from '@/components/ui/switch'
import {
  useCreateDbBackup,
  useDbBackups,
  useDeleteDbBackup,
  useDownloadDbBackup,
  useUpdateDbBackupSchedule,
} from '@/hooks/use-db-backups'
import type { BackupInfo } from '@/api/generated'
import { BACKUP_SCHEDULE_BOUNDS, validateBackupSchedule, type BackupScheduleDraft } from '@/lib/backup'
import { formatBytes, formatDateTime } from '@/lib/format'

/**
 * 库文件快照备份卡（维护页，任务Q 批B）：立即备份（POST /backups）+
 * 快照列表（时间/大小）+ 下载 + 删除。数据获取全部走 use-db-backups hooks
 * （铁律 7），本组件只渲染与弹窗态。
 *
 * 语义备注：快照含口令哈希，删除为物理删除（二次确认）；v1 恢复 = 停服
 * 替换库文件的手动流程（提示行常驻，无在线恢复端点）。
 *
 * 2026-10-03 热生效批：调度参数可直接编辑（PUT /backups/schedule）——
 * 三键草稿态 + 保存提交；校验在 lib/backup.ts（组件零业务规则）；服务端
 * 先持久化再热生效、无需重启（周期从保存时刻重新起算，提示行如实注明）。
 */
export function DbBackupCard() {
  const { data } = useDbBackups()
  const create = useCreateDbBackup()
  const del = useDeleteDbBackup()
  const download = useDownloadDbBackup()
  const updateSchedule = useUpdateDbBackupSchedule()
  // 待确认删除的快照名：非 null 即打开确认弹窗
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)
  // 调度参数草稿：null = 未编辑（展示服务端生效值）；任一输入变更即入草稿
  const [schedDraft, setSchedDraft] = useState<BackupScheduleDraft | null>(null)

  const items = data?.items ?? []
  const schedule = data?.schedule
  const sched: BackupScheduleDraft | null = schedDraft ?? (schedule
    ? { enabled: schedule.enabled, intervalHours: schedule.intervalHours, retention: schedule.retention }
    : null)

  const onCreate = (): void => {
    create.mutate(undefined, {
      onSuccess: (info) =>
        toast.success(`快照完成：${info.name}（${formatBytes(info.sizeBytes)}）`),
      onError: (err) => toast.error(err instanceof Error ? err.message : String(err)),
    })
  }

  const confirmDelete = (): void => {
    if (!pendingDelete) return
    const name = pendingDelete
    setPendingDelete(null)
    del.mutate(name, {
      onSuccess: () => toast.success(`快照已删除：${name}`),
      onError: (err) => toast.error(`删除失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const onDownload = (info: BackupInfo): void => {
    download.mutate(info, {
      onSuccess: (done) => toast.success(`已下载 ${done.name}（${formatBytes(done.sizeBytes)}）`),
      onError: (err) => toast.error(err instanceof Error ? err.message : String(err)),
    })
  }

  // 数字输入变更：空串/非数不更新草稿（受控值维持原样，用户续输即可覆盖）
  const setSchedNum = (key: 'intervalHours' | 'retention', raw: string): void => {
    if (!sched) return
    const n = Number(raw)
    if (raw.trim() === '' || !Number.isFinite(n)) return
    setSchedDraft({ ...sched, [key]: n })
  }

  const onSaveSchedule = (): void => {
    if (!sched) return
    const err = validateBackupSchedule(sched)
    if (err) {
      toast.error(err)
      return
    }
    updateSchedule.mutate(sched, {
      onSuccess: (s) => {
        setSchedDraft(null)
        toast.success(
          s.enabled
            ? `调度参数已生效：每 ${s.intervalHours}h · 保留 ${s.retention} 份（无需重启）`
            : `已保存：自动备份停用（手动触发仍可用），保留 ${s.retention} 份`,
        )
      },
      onError: (err) =>
        toast.error(`调度参数保存失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  return (
    <div className="chart-card">
      <h3>库文件快照备份</h3>
      <p>SQLite 库文件在线快照（不停服）</p>
      {sched ? (
        <div className="settings-grid">
          <label className="settings-switch">
            <Switch
              checked={sched.enabled}
              onCheckedChange={(checked) => setSchedDraft({ ...sched, enabled: checked })}
            />
            <span>自动备份</span>
          </label>
          <label className="settings-field">
            <span>快照间隔（小时）</span>
            <input
              type="number"
              min={BACKUP_SCHEDULE_BOUNDS.intervalHours.min}
              max={BACKUP_SCHEDULE_BOUNDS.intervalHours.max}
              step={1}
              value={sched.intervalHours}
              onChange={(e) => setSchedNum('intervalHours', e.target.value)}
            />
            <small>{BACKUP_SCHEDULE_BOUNDS.intervalHours.min}–{BACKUP_SCHEDULE_BOUNDS.intervalHours.max} h</small>
          </label>
          <label className="settings-field">
            <span>保留份数</span>
            <input
              type="number"
              min={BACKUP_SCHEDULE_BOUNDS.retention.min}
              max={BACKUP_SCHEDULE_BOUNDS.retention.max}
              step={1}
              value={sched.retention}
              onChange={(e) => setSchedNum('retention', e.target.value)}
            />
            <small>{BACKUP_SCHEDULE_BOUNDS.retention.min}–{BACKUP_SCHEDULE_BOUNDS.retention.max} 份，超出自动删最旧</small>
          </label>
        </div>
      ) : (
        <p className="grid-empty">调度参数加载中…</p>
      )}
      <div className="settings-actions">
        <button
          className="save-btn"
          type="button"
          disabled={!sched || updateSchedule.isPending}
          onClick={onSaveSchedule}
        >
          {updateSchedule.isPending ? '保存中…' : '保存调度参数'}
        </button>
        <span className="save-tip">保存即生效，无需重启 · 间隔周期从保存时刻重新起算</span>
      </div>
      <div className="settings-actions">
        <button className="save-btn" type="button" onClick={onCreate} disabled={create.isPending}>
          {create.isPending ? '快照中…' : '立即备份'}
        </button>
        <span className="save-tip">恢复 = 停服后用快照替换数据目录中的 qimeng.db（文档化手动流程）</span>
      </div>
      {items.length === 0 ? (
        <p className="grid-empty">暂无快照——点「立即备份」生成第一份，或等待自动备份</p>
      ) : (
        <table className="log-table">
          <thead>
            <tr>
              <th style={{ width: 180 }}>时间</th>
              <th style={{ width: 100 }}>大小</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((s) => (
              <tr key={s.name}>
                <td>{formatDateTime(s.createdAt)}</td>
                <td>{formatBytes(s.sizeBytes)}</td>
                <td>
                  <button
                    className="pill"
                    type="button"
                    disabled={download.isPending}
                    onClick={() => onDownload(s)}
                  >
                    下载
                  </button>{' '}
                  <button
                    className="pill"
                    type="button"
                    disabled={del.isPending}
                    onClick={() => setPendingDelete(s.name)}
                  >
                    删除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ConfirmDialog
        open={pendingDelete !== null}
        title="删除快照"
        description={`快照 ${pendingDelete ?? ''} 将被物理删除，不可恢复。确认删除？`}
        confirmText="删除"
        cancelText="取消"
        danger
        onConfirm={confirmDelete}
        onOpenChange={(open) => {
          if (!open) setPendingDelete(null)
        }}
      />
    </div>
  )
}
