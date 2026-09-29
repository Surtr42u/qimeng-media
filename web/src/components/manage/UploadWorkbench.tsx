import { useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { toast } from 'sonner'
import type { Library } from '@/api/generated'
import { AuthorSuggestField, type AuthorAttachValue } from '@/components/manage/AuthorSuggestField'
import { CreateDirDialog } from '@/components/manage/CreateDirDialog'
import { DirTreeNodes } from '@/components/manage/DirTree'
import { SourceSelectField } from '@/components/manage/SourceSelectField'
import { StagedUploadList } from '@/components/manage/StagedUploadList'
import { UploadQueueTable } from '@/components/manage/UploadQueueTable'
import { Pill } from '@/components/ui/pill'
import { mergeClientConfig, useConfig } from '@/hooks/use-config'
import { useDirTree } from '@/hooks/use-libraries'
import { BYTES_PER_MB, useUploadQueue, type StagedFileInput } from '@/hooks/use-upload'
import { batchFailureReason } from '@/lib/batch'
import {
  collectDroppedFiles,
  extractDropEntries,
  type DroppedFile,
  type EntryLike,
} from '@/lib/folder-upload'
import { makeStagedItem, type BatchAttachDefaults, type StagedUploadItem } from '@/lib/staged-upload'

/**
 * 上传工作台（2026-09-28 重做替换原 UploadCard，交互同构 App 上传页
 * UploadScreen，只学交互不搬实现；2026-09-29 内部分节重排对齐 App 配置区
 * 固化常驻版动线，只动结构与观感、上传/挂靠/队列管道零改动）。单列自上而下：
 * 选库（目标库必选 → 目标目录树）→ 批次默认（作者/来源，新进项自动继承 +
 * 应用到全部）→ 添加文件入口（点击/拖拽，常驻）→ 暂存列表（空态给引导，
 * 有项时逐项展开校对）→ 开始上传（有暂存项才出现）→ 上传队列。
 *
 * - 批次目标：目标库 pills（必选）+ 目标目录树（库内相对路径，'' = 库根；
 *   支持在当前目录下新建子目录）。
 * - 批次挂靠默认：作者联想 + 来源多选（来源以作者为前提，服务端口径），
 *   新进暂存项自动继承；「应用到全部」整批重刷（批次未配来源不清逐项来源）。
 * - 暂存区：点击/拖拽进列表（目录经 lib/folder-upload.ts 递归展开），逐项
 *   展开编辑作品名（基名+锁定扩展名+序号联想）/作者/来源（StagedUploadList）。
 * - 门禁：批次库必选；超限项开始上传时本地拦截、留在暂存区（文案与 App 对齐）；
 *   类型白名单不在前端复制——服务端四道校验唯一口径，4xx 文案原样透传展示。
 * - 上传：hooks/use-upload.ts 串行队列（严格串行/切路由 abort/目标入队快照），
 *   每条 201 后自动挂靠（201 先后 PUT /assets/{id}/authors 与
 *   PUT /authors/{id}/sources mode=append，挂靠失败落专项态不重试）。
 */

/** 超限拦截文案（与 App blockText 同口径：列明上限与被拦文件名） */
function overLimitBlockText(limitMb: number | null, blocked: StagedUploadItem[]): string {
  const names = blocked.map((it) => it.displayName).join('、')
  return `以下文件超过服务端上限 ${limitMb ?? '—'} MB，已停止上传：${names}`
}

export function UploadWorkbench({ libraries }: { libraries: Library[] }) {
  // ---- 批次目标（会话态；开始上传时刻随 enqueue 快照进每条队列条目）----
  const [libId, setLibId] = useState('')
  // 目标目录 = 库内相对路径（协议 dir 参数，'' = 库根）；切库必须重选（旧库路径对新库无意义）
  const [dir, setDir] = useState('')
  // ---- 批次默认挂靠（新进暂存项继承源）----
  const [batchAuthor, setBatchAuthor] = useState<AuthorAttachValue | null>(null)
  const [batchSources, setBatchSources] = useState<string[]>([])
  // ---- 暂存区 ----
  const [staged, setStaged] = useState<StagedUploadItem[]>([])
  const [dragOver, setDragOver] = useState(false)
  const [blockMsg, setBlockMsg] = useState<string | null>(null)
  const [createDirOpen, setCreateDirOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const enabled = libId !== ''
  const hasBatchAuthor = !!batchAuthor?.authorId
  // 目录树复用既有数据源（GET /dirs）；未选库时挂起不发请求
  const { data: tree } = useDirTree(libId, enabled)
  const { data: config } = useConfig()
  const maxBytesMb = config ? mergeClientConfig(config).upload.maxBytesMb : null

  const queue = useUploadQueue({
    maxBytesMb,
    onItemSettled: (item) => {
      if (item.status === 'done') toast.success(`「${item.name}」上传完成`)
      else if (item.status === 'attach-failed')
        toast.warning(`「${item.name}」${item.errorText ?? '已入库但挂靠失败'}`)
      else if (item.status === 'failed')
        toast.error(`「${item.name}」上传失败：${item.errorText ?? '未知错误'}`)
    },
  })

  /** 进暂存（点击选择 / 拖文件 / 拖目录三管道统一入口；继承批次默认挂靠） */
  const ingest = (inputs: readonly (File | DroppedFile)[]): void => {
    if (inputs.length === 0) return
    const defaults: BatchAttachDefaults = { author: batchAuthor, sources: batchSources }
    const items = inputs.map((input) =>
      input instanceof File
        ? makeStagedItem(input, undefined, defaults)
        : makeStagedItem(input.file, input.relativePath, defaults),
    )
    setStaged((prev) => [...prev, ...items])
  }

  const pickFiles = (e: ChangeEvent<HTMLInputElement>): void => {
    const files = Array.from(e.target.files ?? [])
    e.target.value = '' // 置空以允许重复选择同一文件
    ingest(files)
  }

  const onDrop = (e: DragEvent<HTMLDivElement>): void => {
    e.preventDefault()
    setDragOver(false)
    const dt = e.dataTransfer
    // entry 提取必须在本事件回调内同步完成（DataTransferItem 派发结束即失效，
    // 见 lib/folder-upload.ts）；空列表 = 环境不支持 webkitGetAsEntry 或拖的
    // 不是文件——回退 files 列表（纯文件拖拽/旧浏览器行为不变）
    const entries: EntryLike[] = extractDropEntries(dt.items)
    if (entries.length === 0) {
      ingest(Array.from(dt.files ?? []))
      return
    }
    void (async () => {
      try {
        const dropped: DroppedFile[] = (
          await Promise.all(entries.map((entry) => collectDroppedFiles(entry)))
        ).flat()
        ingest(dropped)
      } catch (err) {
        // 目录遍历失败宁可整批不入暂存，也不静默丢文件
        toast.error(`读取拖入的文件夹失败：${batchFailureReason(err)}`)
      }
    })()
  }

  /** 批次默认一键应用到全部暂存项（口径同 App：作者整批覆盖；批次未配来源时不清逐项来源） */
  const applyBatchToAll = (): void => {
    if (!batchAuthor?.authorId) return
    setStaged((prev) =>
      prev.map((it) => ({
        ...it,
        attachAuthor: batchAuthor,
        attachSources: batchSources.length > 0 ? [...batchSources] : it.attachSources,
      })),
    )
  }

  /** 暂存条目 → 队列入队载荷（挂靠只在作者已定时带上；来源以作者为前提） */
  const toStagedFileInput = (it: StagedUploadItem): StagedFileInput => ({
    file: it.file,
    relativePath: it.relativePath,
    uploadBaseName: it.uploadBaseName?.trim() ? it.uploadBaseName : undefined,
    attach:
      it.attachAuthor?.authorId != null
        ? {
            authorId: it.attachAuthor.authorId,
            authorName: it.attachAuthor.displayName ?? it.attachAuthor.authorName ?? it.attachAuthor.authorId,
            sources: it.attachSources,
          }
        : undefined,
  })

  /** 开始上传（门禁：批次库必选；超限项本地拦截、留在暂存区，其余照常入队） */
  const beginUpload = (): void => {
    if (staged.length === 0) return
    if (!enabled) {
      setBlockMsg('先选择目标库')
      return
    }
    const overLimit =
      maxBytesMb != null ? staged.filter((it) => it.sizeBytes > maxBytesMb * BYTES_PER_MB) : []
    if (overLimit.length === staged.length) {
      setBlockMsg(overLimitBlockText(maxBytesMb, overLimit))
      return
    }
    const allowed = staged.filter((it) => !overLimit.includes(it))
    queue.enqueue(allowed.map(toStagedFileInput), { libraryId: libId, dir })
    setStaged(overLimit) // 被拦项留在暂存区待处理（App 同款语义）
    setBlockMsg(overLimit.length > 0 ? overLimitBlockText(maxBytesMb, overLimit) : null)
  }

  /** 切库同时清空已选目录：目录是库内相对路径，跨库残留会指到错误位置 */
  const onLibChange = (next: string): void => {
    setLibId(next)
    setDir('')
  }

  // 队列聚合行（口径同 App queueSummary：取消不计失败；挂靠失败单独分列）
  const doneCount = queue.items.filter((it) => it.status === 'done').length
  const failedCount = queue.items.filter((it) => it.status === 'failed').length
  const attachFailedCount = queue.items.filter((it) => it.status === 'attach-failed').length

  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>上传</h3>
      </div>
      <p className="rank-note">
        动线：选库 → 放文件 → 逐项校对 → 开始上传
        {maxBytesMb != null ? ` · 单文件上限 ${maxBytesMb} MB，超限项开始上传时拦截` : ' · 类型与大小校验以服务端为准'}
      </p>

      {/* —— 选库（配置区常驻）：目标库（必选）→ 目标目录，未选库给引导 —— */}
      <div className="upload-subhead">目标库（必选）</div>
      <div className="source-chips">
        {libraries.map((l) =>
          l.id ? (
            <Pill key={l.id} active={libId === l.id} onClick={() => onLibChange(l.id!)}>
              {l.name}
            </Pill>
          ) : null,
        )}
        {libraries.length === 0 && <span className="source-empty-hint">暂无可选库——先到「库管理」注册媒体库</span>}
      </div>
      <div className="upload-subhead">
        目标目录 <small className="staged-sub">已选：{dir === '' ? '库根' : dir}</small>
      </div>
      {enabled && tree ? (
        <>
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
          <div className="settings-actions" style={{ marginTop: 8 }}>
            <Pill onClick={() => setCreateDirOpen(true)} title="在当前目录下新建子目录">
              + 新建子目录
            </Pill>
          </div>
        </>
      ) : (
        <p className="grid-empty">{enabled ? '目录树加载中…' : '先选择目标库，目录树在此展示。'}</p>
      )}

      {/* —— 批次默认（挂靠批）：新进暂存项自动继承；已有暂存项可「应用到全部」 —— */}
      <div className="upload-subhead">批次挂靠默认 <small className="staged-sub">新进暂存项自动继承</small></div>
      <AuthorSuggestField
        label="批次作者（可选，联想选择）"
        attachedHint="已选批次作者——新进暂存项自动继承，可逐项改"
        value={batchAuthor}
        onChange={setBatchAuthor}
      />
      <SourceSelectField
        label="批次来源（可选）"
        hint="留空 = 不改动该作者既有来源；已选来源上传后并入其来源记录（不覆盖）"
        disabledHint="先选择批次作者后才能设置来源"
        disabled={!hasBatchAuthor}
        selected={batchSources}
        onChange={setBatchSources}
      />
      <div className="settings-actions" style={{ marginTop: 8 }}>
        <Pill onClick={applyBatchToAll} disabled={!hasBatchAuthor || staged.length === 0} title="把批次作者/来源覆盖到全部暂存项">
          应用到全部（{staged.length} 项）
        </Pill>
      </div>

      {/* —— 放文件：添加文件入口（点击/拖拽统一入口，常驻） —— */}
      <div className="upload-subhead">添加文件 <small className="staged-sub">目录递归展开，落库到上方所选库/目录</small></div>
      <div
        className={`upload-drop${dragOver ? ' over' : ''}`}
        role="button"
        aria-label="选择或拖入要上传的文件或文件夹"
        onClick={() => inputRef.current?.click()}
        onDragOver={(e) => {
          e.preventDefault()
          setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
      >
        点击选择文件，或把文件/文件夹拖到这里
      </div>
      <input ref={inputRef} type="file" multiple hidden onChange={pickFiles} aria-label="选择要上传的文件" />

      {/* —— 逐项校对：暂存列表（空态给引导；有项时逐条展开编辑） —— */}
      <div className="upload-subhead">暂存列表（{staged.length}） <small className="staged-sub">点「编辑」逐项校对作品名/作者/来源</small></div>
      <StagedUploadList
        items={staged}
        libraryId={libId}
        onPatch={(id, patch) => setStaged((prev) => prev.map((it) => (it.id === id ? { ...it, ...patch } : it)))}
        onRemove={(id) => setStaged((prev) => prev.filter((it) => it.id !== id))}
      />

      {/* —— 开始上传（有暂存项才出现）+ 门禁拦截文案（超限/未选库前置提示） —— */}
      {blockMsg && (
        <p className="rank-note" style={{ color: 'var(--danger)' }}>
          {blockMsg}{' '}
          <Pill onClick={() => setBlockMsg(null)}>知道了</Pill>
        </p>
      )}
      {(staged.length > 0 || queue.isBusy || queue.hasFinished) && (
        <div className="settings-actions" style={{ marginTop: 12 }}>
          {staged.length > 0 && (
            <button
              className="save-btn"
              type="button"
              disabled={!enabled}
              onClick={beginUpload}
            >
              开始上传（{staged.length} 个）
            </button>
          )}
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
      )}
      {staged.length > 0 && !enabled && (
        <small className="staged-sub">先在上方选择目标库，再开始上传。</small>
      )}

      {/* —— 上传队列（常驻；逐条串行，挂靠在每条 201 之后自动执行） —— */}
      <div className="upload-subhead">上传队列</div>
      {queue.items.length === 0 ? (
        <p className="grid-empty">暂无上传任务——点「开始上传」后，逐条进度与结果在这里显示。</p>
      ) : (
        <>
          <p className="rank-note">
            共 {queue.items.length} 个 · 成功 {doneCount} · 失败 {failedCount}
            {attachFailedCount > 0 ? ` · 挂靠失败 ${attachFailedCount}` : ''}
          </p>
          <UploadQueueTable items={queue.items} libraries={libraries} />
        </>
      )}

      {enabled && (
        <CreateDirDialog
          open={createDirOpen}
          libraryId={libId}
          parentPath={dir}
          onClose={() => setCreateDirOpen(false)}
        />
      )}
    </div>
  )
}
