import type { Library } from '@/api/generated'
import type { UploadItem } from '@/hooks/use-upload'
import { formatBytes } from '@/lib/format'

/**
 * 上传队列表格（2026-09-29 自 UploadWorkbench 拆出的纯渲染子组件，行内
 * 口径零改动）：文件/挂靠/目标/大小/进度/状态六列；目标与挂靠显示的都是
 * 入队快照（item.targetLibraryId/targetDir/attach），不随当前选择器变化；
 * 失败原因在状态列透传（4xx 服务端文案原样展示）。
 */

/** 状态列文案（uploading 按 percent 分两段：字节传输中 / 服务端入库处理中） */
function statusText(item: { status: string; percent: number }): string {
  switch (item.status) {
    case 'queued':
      return '排队中'
    case 'uploading':
      return item.percent < 100 ? `上传中 ${item.percent}%` : '服务器处理中…'
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

export function UploadQueueTable({ items, libraries }: { items: UploadItem[]; libraries: Library[] }) {
  return (
    <table className="log-table">
      <thead>
        <tr>
          <th>文件</th>
          <th style={{ width: 150 }}>挂靠</th>
          <th style={{ width: 140 }}>目标</th>
          <th style={{ width: 90 }}>大小</th>
          <th style={{ width: 110 }}>进度</th>
          <th style={{ width: 150 }}>状态</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => {
          // 库被删等查不到名时回落显示 ID（仍是快照真值）
          const libName =
            libraries.find((l) => l.id === item.targetLibraryId)?.name ?? item.targetLibraryId
          const targetText = `${libName || ''}/${item.targetDir === '' || item.targetDir === undefined ? '库根' : item.targetDir}`
          return (
            <tr key={item.id}>
              <td style={{ maxWidth: 240 }}>
                <div
                  style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  title={item.originalName ?? item.name}
                >
                  {item.name}
                </div>
                {item.originalName && <div className="staged-sub">原文件名：{item.originalName}</div>}
              </td>
              <td style={{ maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                title={item.attach ? `${item.attach.authorName} · 来源 ${item.attach.sources.join('、')}` : '不挂靠'}>
                {item.attach
                  ? `${item.attach.authorName}${item.attach.sources.length > 0 ? ` · 来源×${item.attach.sources.length}` : ''}`
                  : '—'}
              </td>
              <td
                style={{ maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                title={targetText}
              >
                {targetText}
              </td>
              <td>{formatBytes(item.sizeBytes)}</td>
              <td>
                <div className="progress">
                  <i style={{ width: `${item.percent}%` }} />
                </div>
              </td>
              <td>
                <span className={item.status === 'done' ? 'upload-done' : undefined}>{statusText(item)}</span>
                {item.errorText && (
                  <div className="upload-err" title={item.errorText}>{item.errorText}</div>
                )}
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
