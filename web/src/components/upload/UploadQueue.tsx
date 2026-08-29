/**
 * 上传队列列表：每项 = 文件名 / 大小 / 进度 / 取消按钮 / 终态结果文案。
 * 纯展示组件（数据由 useUploadQueue 提供）；状态颜色一律走 token。
 */

import { CheckCircle2, CircleX, Loader2, X } from 'lucide-react'
import { formatBytes } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { UploadQueueItem } from '@/components/upload/use-upload-queue'

/** 队列项状态 → 终态文案颜色（tokens.css 无"成功绿"语义色——中性主题，成功用主色表达） */
const STATUS_TEXT_CLASSES: Partial<Record<UploadQueueItem['status'], string>> = {
  done: 'text-[var(--qm-primary)]',
  error: 'text-[var(--qm-destructive)]',
  cancelled: 'text-[var(--qm-text-muted)]',
}

export interface UploadQueueProps {
  items: UploadQueueItem[]
  onCancel: (key: string) => void
}

/** 单条队列项（内部组件，不导出让列表布局不外泄） */
function QueueItem({ item, onCancel }: { item: UploadQueueItem; onCancel: (key: string) => void }) {
  const cancellable = item.status === 'queued' || item.status === 'uploading'
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-[var(--qm-border)] bg-[var(--qm-surface)] p-3">
      <div className="flex items-center gap-2">
        {item.status === 'uploading' && (
          <Loader2 className="size-4 shrink-0 animate-spin text-[var(--qm-primary)]" aria-hidden />
        )}
        <span className="min-w-0 flex-1 truncate text-sm font-medium" title={item.file.name}>
          {item.file.name}
        </span>
        <span className="shrink-0 text-xs text-[var(--qm-text-muted)]">{formatBytes(item.file.size)}</span>
        {cancellable && (
          <button
            type="button"
            className="shrink-0 rounded-md p-1 text-[var(--qm-text-muted)] transition-colors hover:bg-[var(--qm-chip-bg)] hover:text-foreground"
            aria-label="取消上传"
            onClick={() => onCancel(item.key)}
          >
            <X className="size-4" />
          </button>
        )}
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-[var(--qm-chip-bg)]">
        <div
          className="h-full rounded-full bg-[var(--qm-primary)] transition-[width] duration-[var(--qm-duration-fast)]"
          style={{ width: `${item.progress}%` }}
        />
      </div>
      <div className="flex items-center gap-1.5 text-xs">
        {item.status === 'done' && <CheckCircle2 className="size-3.5 shrink-0" aria-hidden />}
        {item.status === 'error' && <CircleX className="size-3.5 shrink-0" aria-hidden />}
        <span className={cn('min-w-0 truncate', STATUS_TEXT_CLASSES[item.status])} title={item.message}>
          {item.status === 'uploading' && `${item.progress}%`}
          {item.status !== 'uploading' && item.message}
        </span>
      </div>
    </div>
  )
}

export function UploadQueue({ items, onCancel }: UploadQueueProps) {
  // 空队列不渲染占位文案：上传区（DropZone）本身就是"这里是上传入口"的提示
  if (items.length === 0) return null
  return (
    <div className="flex flex-col gap-2">
      {items.map((item) => (
        <QueueItem key={item.key} item={item} onCancel={onCancel} />
      ))}
    </div>
  )
}
