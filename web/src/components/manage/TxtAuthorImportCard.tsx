import { useRef, useState, type ChangeEvent } from 'react'
import { toast } from 'sonner'
import type { TxtImportConflict } from '@/api/generated'
import {
  useDeleteImportedTxt, useExportTxtSource, useImportAuthorTxt, useRebuildAuthorTxt, useTxtImportedFiles,
} from '@/hooks/use-authors'
import { UploadDropIcon } from '@/components/manage/UploadDropIcon'
import { LoadingHint } from '@/components/ui/loading-hint'
import { batchFailureReason } from '@/lib/batch'

/**
 * 作者 TXT 导入卡（旧版数据管理「TXT导入作者」的 web 对等物）：
 * 选择 .txt → POST /authors/import-txt（三格式自动识别 + 从全部已导入
 * 片段统一重建，DOMAIN_RULES §6）；列表 = 已导入片段名（同名覆盖）；
 * 删除某片段 → 从剩余片段重建作者与文件关联（作者行保留）；
 * 重新匹配 → POST /authors/import-txt/rebuild（幂等重放已存片段重建关联，
 * 库重建/关联丢失后的修复入口）。
 * 导出（REQ §3.4 手动导出）：每份片段「导出」= GET /authors/import-txt/export
 * 下载原文（往返一致——重导出不触发上传条目保护）。
 * 重导入保护（REQ §3.3 第 10 条）：同文件名重导且新内容缺少上传写入条目时
 * 服务端回 409 TXT_CONFLICT——本卡渲染冲突面板（按作者分组列出将被丢弃的
 * 作品/来源条目）由用户二选一：保留并导入（conflictResolution=keep 自动并回）
 * / 确认移除（remove 按明示替换）/ 取消。
 * 视觉：导入动作复用上传卡的 .upload-drop 虚线导入区语言（点击=选文件，
 * 图标 + 主色点缀做卡内焦点），三行规则说明拆紧凑小列表。
 * （2026-09-17 自 LibraryManagePage.tsx 原样搬出为独立组件，逻辑零改动。）
 */

/** 冲突态：暂存被 409 打断的那次导入原文（二选一后带 resolution 重发原 body） */
interface TxtConflictState {
  filename: string
  content: string
  info: TxtImportConflict
}

/** 409 错误形态：unwrapSdkResult 把响应体（=协议 TxtImportConflict 载荷）与
 *  status 并进同一对象抛出（lib/api-client.ts），这里按此形状收窄 */
type TxtConflictError = Partial<TxtImportConflict> & { status?: number }

export function TxtAuthorImportCard() {
  const { data: files = [], isLoading } = useTxtImportedFiles()
  const importTxt = useImportAuthorTxt()
  const deleteTxt = useDeleteImportedTxt()
  const rebuildTxt = useRebuildAuthorTxt()
  const exportTxt = useExportTxtSource()
  const inputRef = useRef<HTMLInputElement>(null)
  const [conflict, setConflict] = useState<TxtConflictState | null>(null)

  // 失败原因文案统一走 batchFailureReason（lib/batch.ts）：4xx 时 unwrapSdkResult
  // 抛的是 {code,message,status} 错误体（非 Error 实例），弱模式会落成
  // "[object Object]"——与批量操作共用一份提取口径，勿再内联 instanceof 模式。

  const onPick = (e: ChangeEvent<HTMLInputElement>): void => {
    const f = e.target.files?.[0]
    e.target.value = '' // 置空以允许连续导入同名文件
    if (!f) return
    if (!/\.txt$/i.test(f.name)) {
      toast.error('仅支持 .txt 作者清单文件')
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const content = String(reader.result ?? '')
      importTxt.mutate(
        { filename: f.name, content },
        {
          onSuccess: (res) => {
            // 新一次导入成功意味着旧 409 冲突已无意义——清掉残留面板，
            // 避免「面板还开着但内容是上一次的」
            setConflict(null)
            toast.success(`「${f.name}」导入完成：作者 ${res.authorsImported ?? 0}，匹配文件 ${res.filesMatched ?? 0}`)
          },
          onError: (err) => {
            // 409 = 重导入保护：新内容会丢弃上传写入条目，交冲突面板二选一，
            // 不弹错误 toast（这是可恢复的业务分叉，不是失败）
            const conflictErr = err as TxtConflictError
            if (conflictErr.status === 409 && conflictErr.authors != null) {
              setConflict({
                filename: f.name,
                content,
                info: { filename: conflictErr.filename, authors: conflictErr.authors },
              })
              return
            }
            toast.error(`导入失败：${batchFailureReason(err)}`)
          },
        },
      )
    }
    reader.onerror = () => toast.error('读取文件失败，请重试')
    reader.readAsText(f)
  }

  /** 冲突二选一：带 conflictResolution 重发被 409 打断的原 body（原文不重读文件） */
  const resolveConflict = (resolution: 'keep' | 'remove'): void => {
    if (!conflict) return
    importTxt.mutate(
      { filename: conflict.filename, content: conflict.content, conflictResolution: resolution },
      {
        onSuccess: (res) => {
          setConflict(null)
          toast.success(
            `「${conflict.filename}」导入完成：作者 ${res.authorsImported ?? 0}，匹配文件 ${res.filesMatched ?? 0}` +
              (resolution === 'keep' && (res.mergedUploadEntries ?? 0) > 0
                ? `，并回上传条目 ${res.mergedUploadEntries} 条`
                : ''),
          )
        },
        onError: (err) => toast.error(`导入失败：${batchFailureReason(err)}`),
      },
    )
  }

  const onDelete = (name: string): void => {
    deleteTxt.mutate(name, {
      onSuccess: () => toast.success(`已移除「${name}」，并从剩余片段重建作者关联`),
      onError: (err) => toast.error(`移除失败：${batchFailureReason(err)}`),
    })
  }

  const onRebuild = (): void => {
    rebuildTxt.mutate(undefined, {
      onSuccess: (res) =>
        toast.success(`重放完成：${res.authorsImported ?? 0} 位作者 · 关联 ${res.filesMatched ?? 0} 个文件`),
      onError: (err) => toast.error(`重放失败：${batchFailureReason(err)}`),
    })
  }

  const onExport = (name: string): void => {
    // 下载动作在 hook 的 mutationFn 内完成（失败 404 等在此 toast）
    exportTxt.mutate(name, {
      onError: (err) => toast.error(`导出失败：${batchFailureReason(err)}`),
    })
  }

  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>作者 TXT 导入</h3>
        <span className="rank-note">{files.length} 份片段</span>
      </div>
      {/* 导入动作视觉焦点：复用上传卡 .upload-drop 虚线导入区（点击=打开文件选择）。
          空态/有片段态提示文案不同；pending 复用 .disabled 视觉并拦截点击（对齐原按钮 disabled） */}
      <div
        className={`upload-drop${importTxt.isPending ? ' disabled' : ''}`}
        role="button"
        tabIndex={importTxt.isPending ? -1 : 0}
        aria-disabled={importTxt.isPending}
        aria-label="选择作者清单 TXT 并导入"
        onClick={() => {
          if (!importTxt.isPending) inputRef.current?.click()
        }}
        onKeyDown={(e) => {
          // 导入区现在是唯一入口（下方重复按钮已删），键盘可达性补齐：
          // Enter/Space 触发文件选择，对齐原生 button 语义
          if (importTxt.isPending || (e.key !== 'Enter' && e.key !== ' ')) return
          e.preventDefault()
          inputRef.current?.click()
        }}
      >
        {importTxt.isPending ? (
          <b>导入中…</b>
        ) : (
          <>
            <UploadDropIcon />
            <b>选择 TXT 导入</b>
            <div>
              {files.length === 0
                ? '点击选择旧项目导出的作者清单 .txt，开始重建作者关联'
                : '点击选择新的 .txt 清单，同名文件自动覆盖'}
            </div>
          </>
        )}
      </div>
      {/* 规则说明：原一大段灰字拆成紧凑小列表（.rank-note 字号/颜色，换行分层） */}
      <div className="rank-note" style={{ marginTop: 10, display: 'grid', gap: 4 }}>
        <span>· 格式 A / B / C 自动识别</span>
        <span>· 同名文件重复导入为覆盖</span>
        <span>· 删除某份后按「剩余片段全部」重算作者与文件关联，作者行保留</span>
      </div>
      {/* 重导入冲突面板（409 TXT_CONFLICT）：按作者分组列出将被丢弃的上传写入
          条目，二选一后带 conflictResolution 重发原 body（REQ §3.3 第 10 条） */}
      {conflict && (
        <div className="txt-conflict-panel">
          <div className="txt-conflict-head">
            <b>重新导入会移除以下上传挂靠条目</b>
            <span className="txt-conflict-file">
              {conflict.filename === '' ? '（匿名导入）' : conflict.filename}
            </span>
          </div>
          <ul className="txt-conflict-list">
            {(conflict.info.authors ?? []).map((a) => (
              <li key={a.authorId ?? a.displayName ?? ''}>
                <span className="txt-conflict-author">{a.displayName ?? a.authorId}</span>
                <div className="txt-conflict-entries">
                  {(a.works ?? []).map((w) => (
                    <span key={w} className="txt-conflict-chip" title="上传写入的作品行">{w}</span>
                  ))}
                  {(a.sources ?? []).map((s) => (
                    <span key={s} className="txt-conflict-chip" title="上传写入的来源行">来源：{s}</span>
                  ))}
                </div>
              </li>
            ))}
          </ul>
          <div className="txt-conflict-actions">
            <button
              className="save-btn"
              type="button"
              disabled={importTxt.isPending}
              onClick={() => resolveConflict('keep')}
            >
              {importTxt.isPending ? '导入中…' : '保留并导入'}
            </button>
            <button
              className="pill"
              type="button"
              disabled={importTxt.isPending}
              onClick={() => resolveConflict('remove')}
            >
              确认移除
            </button>
            <button className="pill" type="button" disabled={importTxt.isPending} onClick={() => setConflict(null)}>
              取消
            </button>
          </div>
          <p className="rank-note">
            保留 = 把这些上传条目自动并回新内容后替换落库；确认移除 = 按替换语义丢弃（明示生效）
          </p>
        </div>
      )}
      <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 8 }}>
        <button
          className="pill"
          type="button"
          disabled={rebuildTxt.isPending || files.length === 0}
          title="按已导入片段全量重放作者-文件关联（片段不变，幂等）"
          onClick={onRebuild}
        >
          {rebuildTxt.isPending ? '重放中…' : '重新匹配'}
        </button>
        <input
          ref={inputRef}
          type="file"
          accept=".txt,text/plain"
          hidden
          onChange={onPick}
          aria-label="选择作者清单 TXT"
        />
      </div>
      {isLoading ? (
        <LoadingHint />
      ) : files.length === 0 ? (
        <p className="grid-empty">还没有导入片段——从上方选择 .txt 开始。</p>
      ) : (
        <table className="log-table">
          <thead>
            <tr><th>片段文件</th><th style={{ width: 170 }}>操作</th></tr>
          </thead>
          <tbody>
            {files.map((f) => (
              <tr key={f.filename}>
                <td>{f.filename === '' ? '（匿名导入）' : f.filename}</td>
                <td>
                  {/* 导出在「移除」旁，同 pill 风格：下载动作在 hook 内触发浏览器下载 */}
                  <button
                    className="pill"
                    type="button"
                    disabled={exportTxt.isPending}
                    title="下载该片段原文（导出文件可原样再导入）"
                    onClick={() => onExport(f.filename)}
                  >
                    {exportTxt.isPending ? '导出中…' : '导出'}
                  </button>{' '}
                  <button
                    className="pill"
                    type="button"
                    disabled={deleteTxt.isPending}
                    onClick={() => onDelete(f.filename)}
                  >
                    移除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
