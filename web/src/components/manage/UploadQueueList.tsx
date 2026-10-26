import type { Library } from '@/api/generated'
import type { UploadItem } from '@/hooks/use-upload'
import { formatBytes } from '@/lib/format'
import { Pill } from '@/components/ui/pill'

/** 响应式任务列表：文件/目标/关联取入队快照，恢复动作仍转发给原队列，不改变传输状态。 */

/** 状态列文案（uploading 按 percent 分两段：字节传输中 / 服务端入库处理中） */
function statusText(item: { status: string; percent: number }): string {
  switch (item.status) {
    case 'queued':
      return '排队中'
    case 'uploading':
      return item.percent < 100 ? `上传中 ${item.percent}%` : '服务器处理中…'
    case 'needs-file':
      return '需重新选择文件'
    case 'done':
      return '已完成'
    case 'failed':
      return '失败'
    case 'attach-failed':
      return '已入库·挂靠失败'
    case 'canceled':
      return '已取消'
    default:
      return item.status
  }
}

/** needs-file 行的恢复提示（有断点 = 重选后从断点续传；无断点 = 重选后重传） */
function needsFileHint(item: UploadItem): string {
  return item.percent > 0
    ? '页面刷新中断——重新选择同名文件后从断点续传'
    : '页面刷新中断——重新选择同名文件后重新上传'
}

export function UploadQueueList({
  items,
  libraries,
  onResumePick,
  onRemoveItem,
}: {
  items: UploadItem[]
  libraries: Library[]
  /** needs-file 行「重选文件」回调（参数 = 条目 id；工作台接管文件拾取） */
  onResumePick?: (id: string) => void
  /** needs-file 行「移除」回调（参数 = 条目 id；store 同步清理持久化记录） */
  onRemoveItem?: (id: string) => void
}) {
  return <ul className="upload-task-list">
    {items.map(item => {
      const libraryName = libraries.find(library => library.id === item.targetLibraryId)?.name ?? item.targetLibraryId
      const targetText = `${libraryName || '未知库'} / ${item.targetDir || '库根目录'}`
      return <li className={`upload-task upload-task--${item.status}`} key={item.id}>
        <div className="upload-task-head"><strong>{item.name}</strong><span className="upload-task-state">{statusText(item)}</span></div>
        {item.originalName && <p className="rank-note">原文件名：{item.originalName}</p>}
        <div className="upload-task-meta"><span>{formatBytes(item.sizeBytes)}</span><span>{targetText}</span>{item.attach && <span>作者：{item.attach.authorName}{item.attach.sources.length > 0 ? ` · 来源：${item.attach.sources.join('、')}` : ''}</span>}</div>
        {(item.status === 'uploading' || item.status === 'queued' || item.status === 'needs-file' && item.percent > 0) && <progress className="upload-task-progress" max={100} value={item.percent} aria-label={`${item.name} 上传进度`} />}
        {item.status === 'needs-file' && <p className="rank-note">{needsFileHint(item)}</p>}
        {item.errorText && <p className="upload-err">{item.errorText}</p>}
        {item.status === 'needs-file' && <div className="upload-queue-actions"><Pill onClick={() => onResumePick?.(item.id)} title="重新选择同名文件后恢复该条上传">重选文件</Pill><Pill onClick={() => onRemoveItem?.(item.id)} title="从队列移除该条（含持久化记录）">移除</Pill></div>}
      </li>
    })}
  </ul>
}
