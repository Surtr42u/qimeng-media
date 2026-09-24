import { useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { toast } from 'sonner'
import type { Library } from '@/api/generated'
import { AuthorSuggestField, type AuthorAttachValue } from '@/components/manage/AuthorSuggestField'
import { DirTreeNodes } from '@/components/manage/DirTree'
import { SourceSelectField } from '@/components/manage/SourceSelectField'
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select'
import { mergeClientConfig, useConfig } from '@/hooks/use-config'
import { useDirTree } from '@/hooks/use-libraries'
import { useUploadQueue, type UploadItem } from '@/hooks/use-upload'
import { formatBytes } from '@/lib/format'
import type { UploadAuthorAttach } from '@/lib/upload-params'

/**
 * 上传卡（W-1：文件管理页「上传」入口；后端 POST /assets/upload 四道校验
 * 早已就绪，本卡只补前端壳）。流程 = 选库 → 目录树选目标目录（默认库根）
 * → 可选作者/来源挂靠（REQ-上传指定作者与来源 §3.1；仅
 * capabilities.authorAttach===true 的库显示，禁止写死 kind）→ 点击/拖入文件
 * → 队列表（文件名/大小/进度条/状态）→ 逐条 toast。
 *
 * 口径：上传逻辑全在 hooks/use-upload.ts（铁律 7）；大小上限前置读
 * GET /config 的 upload 项（设置页实时生效），类型白名单不在前端复制——
 * 服务端四道校验是唯一口径，4xx 文案原样透传展示；切路由由 hook 卸载
 * 清理自动 abort 整队。挂靠目标（作者身份+来源）在入队时刻与 dir 同一
 * 批次快照：整批生效，入队后改选不影响已排队条目。
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
  // 挂靠两字段（REQ §3.1）：作者/来源均为可选；来源依附作者（未选作者时禁用）
  const [author, setAuthor] = useState<AuthorAttachValue | null>(null)
  const [sources, setSources] = useState<string[]>([])
  const [dragOver, setDragOver] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const enabled = libId !== ''
  // 挂靠字段显隐只看能力声明（capabilities.authorAttach，ADR-0012 接入清单项；
  // 服务端按 kind 注册表单一来源产出）——禁止写死 kind==='normal'（REQ §3.2）
  const selectedLib = libraries.find((l) => l.id === libId)
  const attachEnabled = selectedLib?.capabilities?.authorAttach === true
  const authorAttached =
    author != null && (author.authorId != null || author.authorName != null)
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

  /** 入队挂靠快照（与 dir 同一批次口径）：有作者身份才带 authorId/authorName，
   *  来源仅在挂靠非空时传（协议 source 仅指定作者时合法） */
  const attachSnapshot = (): { author?: UploadAuthorAttach; sources?: string[] } | undefined => {
    if (author?.authorId) return { author: { authorId: author.authorId }, sources }
    if (author?.authorName) return { author: { authorName: author.authorName }, sources }
    return undefined
  }

  const pickFiles = (e: ChangeEvent<HTMLInputElement>): void => {
    const files = Array.from(e.target.files ?? [])
    e.target.value = '' // 置空以允许重复选择同一文件
    queue.enqueue(files, attachSnapshot())
  }

  const onDrop = (e: DragEvent<HTMLDivElement>): void => {
    e.preventDefault()
    setDragOver(false)
    if (!enabled) return
    queue.enqueue(Array.from(e.dataTransfer.files ?? []), attachSnapshot())
  }

  /** 切库同时清空已选目录：目录是库内相对路径，跨库残留会指到错误位置；
   *  挂靠字段同理一并复位（新库不支持挂靠时残留的作者/来源无意义） */
  const onLibChange = (next: string): void => {
    setLibId(next)
    setDir('')
    const nextSupportsAttach =
      libraries.find((l) => l.id === next)?.capabilities?.authorAttach === true
    if (!nextSupportsAttach) {
      setAuthor(null)
      setSources([])
    }
  }

  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>上传</h3>
        <span className="f-years">
          <Select value={libId} onValueChange={onLibChange}>
            <SelectTrigger aria-label="上传目标库" placeholder="选择库…" />
            <SelectContent>
              {/* l.id 协议可选，radix SelectItem 要求非空 string：无 id 的库行
                  （正常不会出现）本就无法作为上传目标，直接不进下拉 */}
              {libraries.map((l) =>
                l.id ? (
                  <SelectItem key={l.id} value={l.id}>{l.name}</SelectItem>
                ) : null,
              )}
            </SelectContent>
          </Select>
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
      {/* 挂靠两字段位于目录选择与文件选择之间（REQ §3.1）：均留空=不指定 */}
      {enabled && attachEnabled && (
        <div className="upload-attach">
          <AuthorSuggestField value={author} onChange={setAuthor} />
          <SourceSelectField selected={sources} onChange={setSources} disabled={!authorAttached} />
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
              <th style={{ width: 140 }}>目标</th>
              <th style={{ width: 90 }}>大小</th>
              <th style={{ width: 130 }}>进度</th>
              <th style={{ width: 160 }}>状态</th>
            </tr>
          </thead>
          <tbody>
            {queue.items.map((item) => {
              // 目标显示 = 入队快照（item.targetLibraryId/targetDir），不随当前选择器变化；
              // 库被删等查不到名时回落显示 ID（仍是快照真值）。未选库被拦截的条目
              // targetLibraryId 是空串（无库名），目录语义仍在——照常显示「/库根」
              const libName = libraries.find((l) => l.id === item.targetLibraryId)?.name ?? item.targetLibraryId
              const targetText = `${libName || ''}/${item.targetDir === '' || item.targetDir === undefined ? '库根' : item.targetDir}`
              return (
                <tr key={item.id}>
                  <td
                    style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                    title={item.name}
                  >
                    {item.name}
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
                    {item.status === 'failed' && item.errorText && (
                      <div className="upload-err" title={item.errorText}>{item.errorText}</div>
                    )}
                  </td>
                </tr>
              )
            })}
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
