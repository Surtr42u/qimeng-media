import { useRef, type ChangeEvent } from 'react'
import { toast } from 'sonner'
import {
  useDeleteImportedTxt, useImportAuthorTxt, useRebuildAuthorTxt, useTxtImportedFiles,
} from '@/hooks/use-authors'
import { UploadDropIcon } from '@/components/manage/UploadDropIcon'

/**
 * 作者 TXT 导入卡（旧版数据管理「TXT导入作者」的 web 对等物）：
 * 选择 .txt → POST /authors/import-txt（三格式自动识别 + 从全部已导入
 * 片段统一重建，DOMAIN_RULES §6）；列表 = 已导入片段名（同名覆盖）；
 * 删除某片段 → 从剩余片段重建作者与文件关联（作者行保留）；
 * 重新匹配 → POST /authors/import-txt/rebuild（幂等重放已存片段重建关联，
 * 库重建/关联丢失后的修复入口）。
 * 视觉：导入动作复用上传卡的 .upload-drop 虚线导入区语言（点击=选文件，
 * 图标 + 主色点缀做卡内焦点），三行规则说明拆紧凑小列表。
 * （2026-09-17 自 LibraryManagePage.tsx 原样搬出为独立组件，逻辑零改动。）
 */
export function TxtAuthorImportCard() {
  const { data: files = [], isLoading } = useTxtImportedFiles()
  const importTxt = useImportAuthorTxt()
  const deleteTxt = useDeleteImportedTxt()
  const rebuildTxt = useRebuildAuthorTxt()
  const inputRef = useRef<HTMLInputElement>(null)

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
      importTxt.mutate(
        { filename: f.name, content: String(reader.result ?? '') },
        {
          onSuccess: (res) =>
            toast.success(`「${f.name}」导入完成：作者 ${res.authorsImported ?? 0}，匹配文件 ${res.filesMatched ?? 0}`),
          onError: (err) => toast.error(`导入失败：${err instanceof Error ? err.message : String(err)}`),
        },
      )
    }
    reader.onerror = () => toast.error('读取文件失败，请重试')
    reader.readAsText(f)
  }

  const onDelete = (name: string): void => {
    deleteTxt.mutate(name, {
      onSuccess: () => toast.success(`已移除「${name}」，并从剩余片段重建作者关联`),
      onError: (err) => toast.error(`移除失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const onRebuild = (): void => {
    rebuildTxt.mutate(undefined, {
      onSuccess: (res) =>
        toast.success(`重放完成：${res.authorsImported ?? 0} 位作者 · 关联 ${res.filesMatched ?? 0} 个文件`),
      onError: (err) => toast.error(`重放失败：${err instanceof Error ? err.message : String(err)}`),
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
        <p className="grid-empty">加载中…</p>
      ) : files.length === 0 ? (
        <p className="grid-empty">还没有导入片段——从上方选择 .txt 开始。</p>
      ) : (
        <table className="log-table">
          <thead>
            <tr><th>片段文件</th><th style={{ width: 120 }}>操作</th></tr>
          </thead>
          <tbody>
            {files.map((f) => (
              <tr key={f.filename}>
                <td>{f.filename === '' ? '（匿名导入）' : f.filename}</td>
                <td>
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
