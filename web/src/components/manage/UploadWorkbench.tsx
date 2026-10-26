import { useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { toast } from 'sonner'
import type { Library } from '@/api/generated'
import { FileUp, ListChecks, UploadCloud } from 'lucide-react'
import { UploadDestinationCard } from '@/components/manage/UploadDestinationCard'
import { UploadMetadataCard } from '@/components/manage/UploadMetadataCard'
import { UploadQueueList } from '@/components/manage/UploadQueueList'
import { Pill } from '@/components/ui/pill'
import { useUploadTarget } from '@/hooks/use-upload-target'
import { BYTES_PER_MB, useUploadQueue, type StagedFileInput } from '@/hooks/use-upload'
import { batchFailureReason } from '@/lib/batch'
import {
  collectDroppedFiles,
  extractDropEntries,
  type DroppedFile,
  type EntryLike,
} from '@/lib/folder-upload'

/**
 * 上传工作台（2026-09-28 重做替换原 UploadCard，交互同构 App 上传页
 * UploadScreen，只学交互不搬实现；2026-09-29 拍板「暂存了没意义，去掉」——
 * 暂存列表/逐项编辑/开始上传按钮退役，选完文件立即上传（直传）。位置与可选挂靠在左，添加文件在右，任务结果单独全宽展示。
 * 目录按需展开；挂靠编辑器折叠不丢值，入队摘要始终展示当前目标。
 *
 * - 批次目标：目标库 pills（必选）+ 目标目录树（库内相对路径，'' = 库根；
 *   支持在当前目录下新建子目录）。
 * - 批次挂靠默认：作者联想 + 来源多选（来源以作者为前提，服务端口径），
 *   选文件时刻随入队快照进每条队列条目；之后改默认只影响下一批。
 * - 直传门禁：批次库必选（未选库选文件 → 横幅提示不传）；超限文件本地
 *   拦截在入队前判、拦下的给横幅提示不传；类型白名单不在前端复制——
 *   服务端四道校验唯一口径，4xx 文案原样透传展示。
 * - 上传：hooks/use-upload.ts 订阅 lib/upload-queue-store 模块级单例串行队列
 *   （严格串行/切路由不取消——队列随页面会话存活/入队快照），
 *   每条 201 后自动挂靠（201 先后 PUT /assets/{id}/authors 与
 *   PUT /authors/{id}/sources mode=append，挂靠失败落专项态不重试）。
 */

/** 超限拦截文案（与 App blockText 同口径：列明上限与被拦文件名） */
function overLimitBlockText(limitMb: number | null, names: string[]): string {
  return `以下文件超过服务端上限 ${limitMb ?? '—'} MB，已停止上传：${names.join('、')}`
}

/** 选取条目统一取文件句柄（点击选择 = File 本身；拖拽目录条目 = DroppedFile.file） */
function fileOf(input: File | DroppedFile): File {
  return input instanceof File ? input : input.file
}

export function UploadWorkbench({ libraries, targetsReady = true }: { libraries: Library[]; targetsReady?: boolean }) {
  const target = useUploadTarget(libraries, targetsReady)
  const { libId, dir, batchAuthor, batchSources, maxBytesMb } = target
  const [dragOver, setDragOver] = useState(false)
  const [blockMsg, setBlockMsg] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  // 刷新恢复条目的重选文件：待恢复条目 id（隐藏 file input 的 onChange 据此回填）
  const [resumePickId, setResumePickId] = useState<string | null>(null)
  const resumeInputRef = useRef<HTMLInputElement>(null)

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

  /** 队列入队载荷（挂靠只在作者已定时带上；来源以作者为前提，快照批次默认） */
  const toUploadInput = (input: File | DroppedFile): StagedFileInput => {
    const author = target.canAttach ? batchAuthor : null
    return {
      file: fileOf(input),
      relativePath: input instanceof File ? undefined : input.relativePath,
      attach:
        author?.authorId != null
          ? {
              authorId: author.authorId,
              authorName: author.displayName ?? author.authorName ?? author.authorId,
              sources: batchSources,
            }
          : undefined,
    }
  }

  /** 直传入队（点击选择 / 拖文件 / 拖目录三管道统一入口；继承批次默认挂靠） */
  const ingest = (inputs: readonly (File | DroppedFile)[]): void => {
    if (inputs.length === 0) return
    if (!target.canAdd) {
      setBlockMsg(`${target.blockedReason}——本次选中的文件未上传`)
      return
    }
    // 超限本地拦截在入队前判：拦下的不传、给横幅提示；全部超限则整批不入队
    const overLimit =
      maxBytesMb != null
        ? inputs.filter((input) => fileOf(input).size > maxBytesMb * BYTES_PER_MB)
        : []
    const allowed = inputs.filter((input) => !overLimit.includes(input))
    if (allowed.length > 0) {
      queue.enqueue(allowed.map(toUploadInput), { libraryId: libId, dir })
    }
    setBlockMsg(
      overLimit.length > 0
        ? overLimitBlockText(maxBytesMb, overLimit.map((input) => fileOf(input).name))
        : null,
    )
  }

  const pickFiles = (e: ChangeEvent<HTMLInputElement>): void => {
    const files = Array.from(e.target.files ?? [])
    e.target.value = '' // 置空以允许重复选择同一文件
    ingest(files)
  }

  const onDrop = (e: DragEvent<HTMLDivElement>): void => {
    e.preventDefault()
    setDragOver(false)
    if (!target.canAdd) { setBlockMsg(target.blockedReason); return }
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
        // 目录遍历失败宁可整批不入队，也不静默丢文件
        toast.error(`读取拖入的文件夹失败：${batchFailureReason(err)}`)
      }
    })()
  }

  /** 恢复条目「重选文件」：记住条目 id 后唤起隐藏 file input（拾取链路与
   *  既有上传同款 input[type=file]；匹配/续传决策在 store，本组件只转发） */
  const requestResumePick = (id: string): void => {
    setResumePickId(id)
    resumeInputRef.current?.click()
  }

  const onResumePicked = (e: ChangeEvent<HTMLInputElement>): void => {
    const file = e.target.files?.[0]
    e.target.value = '' // 置空以允许重复选择同一文件
    if (file !== undefined && resumePickId !== null) {
      queue.resumeWithFile(resumePickId, file)
    }
    setResumePickId(null)
  }

  // 队列聚合行（口径同 App queueSummary：取消不计失败；挂靠失败单独分列；
  // 待重选恢复条目单独计数提示）
  const doneCount = queue.items.filter((it) => it.status === 'done').length
  const failedCount = queue.items.filter((it) => it.status === 'failed').length
  const attachFailedCount = queue.items.filter((it) => it.status === 'attach-failed').length
  const needsFileCount = queue.items.filter((it) => it.status === 'needs-file').length

  const authorLabel = target.canAttach
    ? batchAuthor?.authorId ? batchAuthor.displayName ?? batchAuthor.authorName ?? batchAuthor.authorId : batchAuthor ? '作者待确认' : '不指定作者'
    : '按库规则关联作者'

  return <div className="upload-workbench">
    <div className="upload-layout">
      <div className="upload-setup">
        <UploadDestinationCard key={libId} libraries={libraries} target={target} />
        <UploadMetadataCard target={target} />
      </div>
      <section className="chart-card upload-intake">
        <div className="upload-section-head"><span className="upload-step">03</span><h3>添加文件</h3><UploadCloud aria-hidden="true" /></div>
        <div className={`upload-drop${dragOver ? ' over' : ''}`} role="region" aria-label="拖放上传区"
          onDragOver={event => { event.preventDefault(); setDragOver(true) }}
          onDragLeave={() => setDragOver(false)} onDrop={onDrop}>
          <span className="upload-drop-icon"><FileUp aria-hidden="true" /></span>
          <h4>把文件或文件夹拖到这里</h4>
          <p>文件夹会递归读取，选择后立即上传</p>
          <button className="save-btn" type="button" disabled={!target.canAdd} onClick={() => inputRef.current?.click()}>选择文件并立即上传</button>
          <span className="upload-limit">{maxBytesMb != null ? `单文件上限 ${maxBytesMb} MB` : '类型与大小由服务器校验'}</span>
        </div>
        <div className="upload-destination-summary" role="group" aria-label="本批上传摘要">
          <span className="upload-field-label">本批文件将上传到</span>
          <strong>{target.libraryName}</strong><span className="upload-summary-path">{dir || '库根目录'}</span>
          <div className="upload-summary-tags"><span>{authorLabel}</span>{batchAuthor?.authorId && batchSources.length > 0 && <span>来源：{batchSources.join('、')}</span>}</div>
        </div>
        <div className={`upload-ready${target.canAdd ? ' upload-ready--ok' : ''}`} role="status">{target.blockedReason ?? '已就绪 · 修改左侧设置只影响下一批文件'}{target.configError && <Pill onClick={() => { void target.retryConfig() }}>重新读取上传设置</Pill>}</div>
        {blockMsg && <div className="manage-notice manage-notice--warning" role="alert"><span>{blockMsg}</span><Pill onClick={() => setBlockMsg(null)}>知道了</Pill></div>}
      </section>
    </div>
    <input ref={inputRef} type="file" multiple hidden onChange={pickFiles} aria-label="选择要上传的文件" />
    <input ref={resumeInputRef} type="file" hidden onChange={onResumePicked} aria-label="为刷新恢复的上传条目重新选择同名文件" />
    <section className="chart-card upload-queue" aria-label="上传任务">
      <div className="upload-queue-head"><div><h3><ListChecks aria-hidden="true" />上传队列 <span>{queue.items.length}</span></h3><p className="rank-note">{queue.items.length > 0 ? `成功 ${doneCount} · 失败 ${failedCount} · 关联失败 ${attachFailedCount} · 待重选 ${needsFileCount}` : '本次会话的进度与结果'}</p></div><div className="upload-queue-actions">
        {queue.isBusy && <button className="pill" type="button" onClick={queue.cancelAll}>全部取消</button>}
        {queue.hasFinished && !queue.isBusy && <button className="pill" type="button" title="仅移除已结束任务的列表记录，不删除已上传文件" onClick={queue.clearFinished}>清理已结束记录</button>}
      </div></div>
      {(queue.persistenceHealth.unavailable || queue.persistenceHealth.restoreFailed || queue.persistenceHealth.failedWrites > 0 || queue.persistenceHealth.failedDeletes > 0) && <div role="status" className="manage-notice manage-notice--warning">
        <span>{queue.persistenceHealth.failedDeletes > 0 ? '上传结果不受影响，但旧任务记录删除失败；刷新可能再次显示旧任务，请先核对已入库文件。' : '上传队列存储不可用，本次任务仅当前页面有效，刷新可能无法恢复。'}</span>
        <button type="button" className="secondary-btn" disabled={queue.persistenceHealth.retrying} onClick={() => { void queue.retryPersistence() }}>{queue.persistenceHealth.retrying ? '重试中…' : '重试队列存储'}</button>
      </div>}
      {queue.items.length === 0 ? <div className="upload-queue-empty"><ListChecks aria-hidden="true" /><div><strong>暂无上传任务</strong><p>添加文件后，在这里查看进度、结果和恢复操作。</p></div></div> : <UploadQueueList items={queue.items} libraries={libraries} onResumePick={requestResumePick} onRemoveItem={queue.removeItem} />}
    </section>
  </div>
}
