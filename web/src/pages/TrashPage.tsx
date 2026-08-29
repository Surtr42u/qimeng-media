/**
 * 回收站页（/admin/trash）：回收站列表 + 恢复 + 单条永久删除 + 清空。
 *
 * 数据与操作全走 use-trash hooks（列表按服务端删除时间倒序，客户端不再排序）；
 * 单条删除与清空为不可逆操作，必须经过二次确认弹窗（写法对齐 TagManager 删除确认）；
 * 操作结果以 sonner toast 反馈，失败文案统一 apiErrorText。
 */

import { useState } from 'react'
import { toast } from 'sonner'
import { Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { useTrashClear, useTrashDelete, useTrashItems, useTrashRestore } from '@/hooks/use-trash'
import type { TrashItem } from '@/api/generated'
import { apiErrorText } from '@/pages/_shared/error-text'
import { formatBytes, formatDateTime } from '@/lib/format'

/* ------------------------------ 文案常量 ------------------------------ */

const TEXT_TITLE = '回收站'
const TEXT_COUNT_TEMPLATE = '共 {n} 项'
const TEXT_CLEAR = '清空'
const TEXT_RESTORE = '恢复'
const TEXT_DELETE = '删除'
const TEXT_RESTORE_SUCCESS = '文件已恢复'
const TEXT_DELETE_SUCCESS = '文件已永久删除'
const TEXT_CLEAR_SUCCESS = '回收站已清空'
const TEXT_EMPTY_TITLE = '回收站是空的'
const TEXT_EMPTY_HINT = '删除的文件会先进入回收站，可在此恢复或永久删除'
const TEXT_DELETE_DIALOG_TITLE = '永久删除文件'
const TEXT_DELETE_DIALOG_BODY_TEMPLATE = '将永久删除「{name}」，此操作不可恢复。确定删除？'
const TEXT_CLEAR_DIALOG_TITLE = '清空回收站'
const TEXT_CLEAR_DIALOG_BODY_TEMPLATE = '将永久删除回收站中的 {n} 个文件，此操作不可恢复。确定清空？'
const TEXT_CONFIRM_DELETE = '永久删除'
const TEXT_CONFIRM_CLEAR = '确认清空'
const TEXT_CANCEL = '取消'
const TEXT_DELETED_AT_TEMPLATE = '删除于 {t}'
const TEXT_EXPIRES_AT_TEMPLATE = '保留至 {t}'

/** 文案模板填充（{k} 占位，页面内统一走此辅助，避免散落 replace） */
function fillTemplate(template: string, values: Record<string, string>): string {
  return Object.entries(values).reduce(
    (text, [key, value]) => text.replaceAll(`{${key}}`, value),
    template,
  )
}

export default function TrashPage() {
  const trashQuery = useTrashItems()
  const restore = useTrashRestore()
  const remove = useTrashDelete()
  const clear = useTrashClear()

  const items = trashQuery.data ?? []
  const [deleteTarget, setDeleteTarget] = useState<TrashItem | null>(null)
  const [clearOpen, setClearOpen] = useState(false)

  const handleRestore = async (item: TrashItem) => {
    if (!item.id) return
    try {
      await restore.mutateAsync(item.id)
      toast.success(TEXT_RESTORE_SUCCESS)
    } catch (error) {
      toast.error(apiErrorText(error))
    }
  }

  const handleDelete = async () => {
    if (!deleteTarget?.id) return
    try {
      await remove.mutateAsync(deleteTarget.id)
      setDeleteTarget(null)
      toast.success(TEXT_DELETE_SUCCESS)
    } catch (error) {
      toast.error(apiErrorText(error))
    }
  }

  const handleClear = async () => {
    try {
      await clear.mutateAsync()
      setClearOpen(false)
      toast.success(TEXT_CLEAR_SUCCESS)
    } catch (error) {
      toast.error(apiErrorText(error))
    }
  }

  return (
    <div className="flex flex-col gap-4 px-4 py-4">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-bold">{TEXT_TITLE}</h1>
        {items.length > 0 && (
          <span className="text-xs text-[var(--qm-text-muted)]">
            {fillTemplate(TEXT_COUNT_TEMPLATE, { n: String(items.length) })}
          </span>
        )}
        <Button
          variant="destructive"
          size="sm"
          className="ml-auto"
          disabled={items.length === 0 || clear.isPending}
          onClick={() => setClearOpen(true)}
        >
          {TEXT_CLEAR}
        </Button>
      </div>

      {trashQuery.isLoading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-[76px] w-full rounded-xl" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <div className="flex flex-col items-center gap-2 py-20 text-center">
          <p className="text-sm font-semibold">{TEXT_EMPTY_TITLE}</p>
          <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_EMPTY_HINT}</p>
        </div>
      ) : (
        <ul className="divide-y divide-[var(--qm-divider)] rounded-xl border border-[var(--qm-border)] bg-[var(--qm-surface)]">
          {items.map((item, index) => (
            <li key={item.id ?? index} className="flex items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium" title={item.originalPath ?? item.fileName}>
                  {item.fileName ?? '--'}
                </p>
                {item.originalPath && (
                  <p className="truncate text-xs text-[var(--qm-text-muted)]" title={item.originalPath}>
                    {item.originalPath}
                  </p>
                )}
                <p className="text-xs text-[var(--qm-text-muted)]">
                  {formatBytes(item.sizeBytes)}
                  {item.deletedAt
                    ? ` · ${fillTemplate(TEXT_DELETED_AT_TEMPLATE, { t: formatDateTime(item.deletedAt) })}`
                    : ''}
                  {item.expiresAt
                    ? ` · ${fillTemplate(TEXT_EXPIRES_AT_TEMPLATE, { t: formatDateTime(item.expiresAt) })}`
                    : ''}
                </p>
              </div>
              <Button
                size="sm"
                variant="secondary"
                disabled={restore.isPending}
                onClick={() => void handleRestore(item)}
              >
                {TEXT_RESTORE}
              </Button>
              <Button
                size="icon-sm"
                variant="destructive"
                aria-label={`${TEXT_DELETE} ${item.fileName ?? ''}`}
                disabled={remove.isPending}
                onClick={() => setDeleteTarget(item)}
              >
                <Trash2 className="size-3.5" aria-hidden />
              </Button>
            </li>
          ))}
        </ul>
      )}

      {/* 单条永久删除确认（不可逆） */}
      <Dialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{TEXT_DELETE_DIALOG_TITLE}</DialogTitle>
            <p className="text-sm text-[var(--qm-text-muted)]">
              {fillTemplate(TEXT_DELETE_DIALOG_BODY_TEMPLATE, { name: deleteTarget?.fileName ?? '' })}
            </p>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              {TEXT_CANCEL}
            </Button>
            <Button variant="destructive" disabled={remove.isPending} onClick={() => void handleDelete()}>
              {TEXT_CONFIRM_DELETE}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 清空回收站确认（不可逆） */}
      <Dialog open={clearOpen} onOpenChange={setClearOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{TEXT_CLEAR_DIALOG_TITLE}</DialogTitle>
            <p className="text-sm text-[var(--qm-text-muted)]">
              {fillTemplate(TEXT_CLEAR_DIALOG_BODY_TEMPLATE, { n: String(items.length) })}
            </p>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setClearOpen(false)}>
              {TEXT_CANCEL}
            </Button>
            <Button variant="destructive" disabled={clear.isPending} onClick={() => void handleClear()}>
              {TEXT_CONFIRM_CLEAR}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
