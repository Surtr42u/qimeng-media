import { useState } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import {
  useCreateDbBackup,
  useDbBackups,
  useDeleteDbBackup,
  useDownloadDbBackup,
} from '@/hooks/use-db-backups'
import type { BackupInfo } from '@/api/generated'
import { formatBytes, formatDateTime } from '@/lib/format'

/**
 * 库文件快照备份卡（维护页，任务Q 批B）：立即备份（POST /backups）+
 * 快照列表（时间/大小）+ 下载 + 删除。数据获取全部走 use-db-backups hooks
 * （铁律 7），本组件只渲染与弹窗态。
 *
 * 语义备注：快照含口令哈希，删除为物理删除（二次确认）；v1 恢复 = 停服
 * 替换库文件的手动流程（提示行常驻，无在线恢复端点）。
 */
export function DbBackupCard() {
  const { data } = useDbBackups()
  const create = useCreateDbBackup()
  const del = useDeleteDbBackup()
  const download = useDownloadDbBackup()
  // 待确认删除的快照名：非 null 即打开确认弹窗
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)

  const items = data?.items ?? []
  const schedule = data?.schedule
  const scheduleText = schedule
    ? `自动备份：${schedule.enabled ? '已启用' : '未启用'} · 每 ${schedule.intervalHours}h · 保留 ${schedule.retention} 份`
    : '自动备份：加载中…'

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

  return (
    <div className="chart-card">
      <h3>库文件快照备份</h3>
      <p>SQLite 库文件在线快照（不停服）· {scheduleText}</p>
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
