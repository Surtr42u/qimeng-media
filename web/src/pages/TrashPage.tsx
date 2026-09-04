import { useState } from 'react'
import { toast } from 'sonner'
import { LOCALE_ZH } from '@/lib/constants'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import {
  useDeleteTrashItem, useEmptyTrash, useRestoreTrash, useTrash,
} from '@/hooks/use-trash'
import type { TrashItem } from '@/api/generated'

/**
 * 回收站页（维护页「回收站」入口卡 → /app/maintenance/trash）。
 * 删除资产 = 移入回收站（铁律 4）；本页提供恢复 / 彻底删除 / 清空，
 * 后两者是显式的物理删除管理操作——全部 confirm 二次确认。
 */

function formatBytes(n: number): string {
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(1)} GB`
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(1)} MB`
  if (n >= 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${n} B`
}

export default function TrashPage() {
  const { data: items = [], isLoading } = useTrash()
  const restore = useRestoreTrash()
  const removeOne = useDeleteTrashItem()
  const emptyAll = useEmptyTrash()

  const doRestore = (it: TrashItem): void => {
    restore.mutate(it.id ?? '', {
      onSuccess: () => toast.success(`「${it.fileName}」已恢复（冲突时自动重命名）`),
      onError: (err) => toast.error(`恢复失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const doRemoveOne = (it: TrashItem): void => {
    removeOne.mutate(it.id ?? '', {
      onSuccess: () => toast.success('已彻底删除'),
      onError: (err) => toast.error(`删除失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const doEmpty = (): void => {
    emptyAll.mutate(undefined, {
      onSuccess: () => toast.success('回收站已清空'),
      onError: (err) => toast.error(`清空失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  // W-2：window.confirm 换 ConfirmDialog（原型风格二次确认），状态记待确认目标
  const [removeTarget, setRemoveTarget] = useState<TrashItem | null>(null)
  const [emptyConfirmOpen, setEmptyConfirmOpen] = useState(false)

  const totalBytes = items.reduce((acc, it) => acc + (it.sizeBytes ?? 0), 0)

  return (
    <div className="page" id="page-maintenance-trash">
      <div className="page-head">
        <h2>回收站</h2>
        <p>删除的文件先进回收站；恢复或彻底清除都在这里 · 共 {items.length} 项 / {formatBytes(totalBytes)}</p>
      </div>

      <div className="settings-actions">
        <button className="save-btn" type="button" onClick={() => setEmptyConfirmOpen(true)} disabled={emptyAll.isPending || items.length === 0}>
          清空回收站
        </button>
        <span className="save-tip" hidden={emptyAll.isPending}>物理删除不可恢复</span>
      </div>

      <div className="rank-card">
        {isLoading ? (
          <p className="grid-empty">加载中…</p>
        ) : items.length === 0 ? (
          <p className="grid-empty">回收站是空的——删除资产后会出现在这里。</p>
        ) : (
          <table className="log-table">
            <thead>
              <tr><th>文件名</th><th>原路径</th><th>大小</th><th>删除时间</th><th>操作</th></tr>
            </thead>
            <tbody>
              {items.map((it) => (
                <tr key={it.id}>
                  <td>{it.fileName}</td>
                  <td style={{ maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={it.originalPath}>{it.originalPath}</td>
                  <td>{formatBytes(it.sizeBytes ?? 0)}</td>
                  <td>{it.deletedAt ? new Date(it.deletedAt).toLocaleString(LOCALE_ZH) : '-'}</td>
                  <td>
                    <button className="pill" type="button" onClick={() => doRestore(it)}>恢复</button>
                    <button className="pill" type="button" onClick={() => setRemoveTarget(it)}>彻底删除</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 彻底删除（物理删除类 → danger 深色强调） */}
      <ConfirmDialog
        open={removeTarget !== null}
        title={`彻底删除「${removeTarget?.fileName ?? ''}」？`}
        description="物理删除不可恢复。"
        confirmText="彻底删除"
        cancelText="取消"
        danger
        onConfirm={() => {
          const it = removeTarget
          setRemoveTarget(null)
          if (it) doRemoveOne(it)
        }}
        onOpenChange={(next) => { if (!next) setRemoveTarget(null) }}
      />

      {/* 清空回收站（清空类 → danger 深色强调）；空态由按钮 disabled 拦截，不进弹窗 */}
      <ConfirmDialog
        open={emptyConfirmOpen}
        title="清空回收站？"
        description={`${items.length} 个文件将被物理删除，不可恢复。`}
        confirmText="清空"
        cancelText="取消"
        danger
        onConfirm={() => {
          setEmptyConfirmOpen(false)
          doEmpty()
        }}
        onOpenChange={setEmptyConfirmOpen}
      />
    </div>
  )
}
