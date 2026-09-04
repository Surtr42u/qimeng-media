import { useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { toast } from 'sonner'
import type { Library } from '@/api/generated'
import { DirTreeNodes } from '@/components/manage/DirTree'
import { mergeClientConfig, useConfig } from '@/hooks/use-config'
import { useDirTree } from '@/hooks/use-libraries'
import { useUploadQueue, type UploadItem } from '@/hooks/use-upload'
import { formatBytes } from '@/lib/format'

/**
 * 上传卡（W-1：文件管理页「上传」入口；后端 POST /assets/upload 四道校验
 * 早已就绪，本卡只补前端壳）。流程 = 选库 → 目录树选目标目录（默认库根）
 * → 点击/拖入文件 → 队列表（文件名/大小/进度条/状态）→ 逐条 toast。
 *
 * 口径：上传逻辑全在 hooks/use-upload.ts（铁律 7）；大小上限前置读
 * GET /config 的 upload 项（设置页实时生效），类型白名单不在前端复制——
 * 服务端四道校验是唯一口径，4xx 文案原样透传展示；切路由由 hook 卸载
 * 清理自动 abort 整队。
 */

/** 状态列文案（uploading 按 percent 分两段：字节传输中 / 服务端入库处理中） */
function statusText(item: UploadItem): string {
  switch (item.status) {
    case 'queued':
      return '排队中'
    case 'uploading':
      return item.percent < 100 ? `上传中 ${item.percent}%` : '服务器处理中…'
    case 'done':
      return '已完成'
    case 'failed':
      return '失败'
    case 'canceled':
      return '已取消'
  }
}

export function UploadCard({ libraries }: { libraries: Library[] }) {
  const [libId, setLibId] = useState('')
  // 目标目录 = 库内相对路径（协议 dir 参数，'' = 库根）；切库必须重选（旧库路径对新库无意义）
  const [dir, setDir] = useState('')
  const [dragOver, setDragOver] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const enabled = libId !== ''
  // 目录树复用既有数据源（GET /dirs）；未选库时挂起不发请求
  const { data: tree } = useDirTree(libId, enabled)
  const { data: config } = useConfig()
  const maxBytesMb = config ? mergeClientConfig(config).upload.maxBytesMb : null

  const queue = useUploadQueue({
    libraryId: libId,
    dir,
    maxBytesMb,
    onItemSettled: (item) => {
      if (item.status === 'done') toast.success(`「${item.name}」上传完成`)
      else if (item.status === 'failed')
        toast.error(`「${item.name}」上传失败：${item.errorText ?? '未知错误'}`)
    },
  })

  const pickFiles = (e: ChangeEvent<HTMLInputElement>): void => {
    const files = Array.from(e.target.files ?? [])
    e.target.value = '' // 置空以允许重复选择同一文件
    queue.enqueue(files)
  }

  const onDrop = (e: DragEvent<HTMLDivElement>): void => {
    e.preventDefault()
    setDragOver(false)
    if (!enabled) return
    queue.enqueue(Array.from(e.dataTransfer.files ?? []))
  }

  /** 切库同时清空已选目录：目录是库内相对路径，跨库残留会指到错误位置 */
  const onLibChange = (next: string): void => {
    setLibId(next)
    setDir('')
  }

  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>上传</h3>
        <span className="f-years">
          <select value={libId} onChange={(e) => onLibChange(e.target.value)} aria-label="上传目标库">
            <option value="">选择库…</option>
            {libraries.map((l) => (
              <option key={l.id} value={l.id}>{l.name}</option>
            ))}
          </select>
        </span>
      </div>
      <p className="rank-note">
        把本机文件直接传进库（手机 App 直传的 Web 对等物）
        {maxBytesMb != null ? `；单文件上限 ${maxBytesMb} MB，超限不入队` : '；类型与大小校验以服务端为准'}
      </p>
      {enabled && tree && (
        <div className="dir-tree">
          <ul>
            <DirTreeNodes
              node={tree}
              depth={0}
              selectedPath={dir}
              onSelect={(node) => setDir(node.path ?? '')}
            />
          </ul>
        </div>
      )}
      <div
        className={`upload-drop${dragOver ? ' over' : ''}${enabled ? '' : ' disabled'}`}
        role="button"
        aria-disabled={!enabled}
        aria-label="选择或拖入要上传的文件"
        onClick={() => enabled && inputRef.current?.click()}
        onDragOver={(e) => {
          e.preventDefault()
          if (enabled) setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
      >
        {enabled ? (
          <>
            上传到：<b>{dir === '' ? '库根' : dir}</b> · 点击选择文件，或把文件拖到这里
          </>
        ) : (
          '先在右上角选择目标库，再选择/拖入文件'
        )}
      </div>
      <input ref={inputRef} type="file" multiple hidden onChange={pickFiles} aria-label="选择要上传的文件" />
      {queue.items.length > 0 && (
        <table className="log-table">
          <thead>
            <tr>
              <th>文件</th>
              <th style={{ width: 90 }}>大小</th>
              <th style={{ width: 130 }}>进度</th>
              <th style={{ width: 160 }}>状态</th>
            </tr>
          </thead>
          <tbody>
            {queue.items.map((item) => (
              <tr key={item.id}>
                <td
                  style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  title={item.name}
                >
                  {item.name}
                </td>
                <td>{formatBytes(item.sizeBytes)}</td>
                <td>
                  <div className="progress">
                    <i style={{ width: `${item.percent}%` }} />
                  </div>
                </td>
                <td>
                  <span className={item.status === 'done' ? 'upload-done' : undefined}>{statusText(item)}</span>
                  {item.status === 'failed' && item.errorText && (
                    <div className="upload-err" title={item.errorText}>{item.errorText}</div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div style={{ marginTop: 10, display: 'flex', gap: 8 }}>
        <button className="save-btn" type="button" disabled={!enabled} onClick={() => inputRef.current?.click()}>
          选择文件上传
        </button>
        {queue.isBusy && (
          <button className="pill" type="button" onClick={queue.cancelAll}>
            全部取消
          </button>
        )}
        {queue.hasFinished && !queue.isBusy && (
          <button className="pill" type="button" onClick={queue.clearFinished}>
            清除已完成
          </button>
        )}
      </div>
    </div>
  )
}
