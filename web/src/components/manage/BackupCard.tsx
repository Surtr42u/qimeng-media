import { useRef, useState, type ChangeEvent } from 'react'
import { toast } from 'sonner'
import { backupSummaryText, parseLegacyBackupFile, type LegacyBackupSummary } from '@/lib/backup'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { useExportQimengBackup, useImportQimengBackup } from '@/hooks/use-backup'
import type { LegacyBackupImport } from '@/api/generated'

/**
 * 备份导入/导出卡（旧版数据管理「数据备份」的 web 对等物，DOMAIN_RULES §10）：
 * 导出 = GET /export/qimeng-backup 原样下载 qimeng_backup.json（当前库全量，
 * 旧版单文件格式）；导入 = 选择备份 json → 解析校验（lib/backup.ts）→
 * 确认弹窗（旧版「检测到备份数据…是否导入恢复」语义）→ POST /import/
 * qimeng-backup（幂等合并、不删除现有数据）→ 按迁移结果统计 toast。
 * 视觉：导入区复用 .upload-drop 虚线语言（与作者 TXT 导入卡同构）。
 * （2026-09-17 自 LibraryManagePage.tsx 原样搬出为独立组件，逻辑零改动。）
 */
export function BackupCard() {
  const exportBackup = useExportQimengBackup()
  const importBackup = useImportQimengBackup()
  const inputRef = useRef<HTMLInputElement>(null)
  // 待确认的导入载荷：非 null 即打开确认弹窗（旧版语义：确认后才真正上传导入）
  const [pending, setPending] = useState<{ payload: LegacyBackupImport; summary: LegacyBackupSummary } | null>(null)

  const onExport = (): void => {
    exportBackup.mutate(undefined, {
      onSuccess: ({ sizeBytes }) =>
        toast.success(`已导出 qimeng_backup.json（${(sizeBytes / 1024).toFixed(0)} KB）`),
      onError: (err) => toast.error(`导出失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const onPick = async (e: ChangeEvent<HTMLInputElement>): Promise<void> => {
    const f = e.target.files?.[0]
    e.target.value = '' // 置空以允许连续导入同名文件
    if (!f) return
    try {
      setPending(await parseLegacyBackupFile(f))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : String(err))
    }
  }

  const confirmImport = (): void => {
    if (!pending) return
    const { payload, summary } = pending
    setPending(null)
    importBackup.mutate(payload, {
      onSuccess: (res) => {
        toast.success(
          `「${summary.fileName}」导入完成：匹配文件 ${res.assetsMatched ?? 0}/${res.mediaFilesTotal ?? 0}，` +
            `作者 ${res.authorsImported ?? 0}，标签 ${res.tagsImported ?? 0}，事件回放 ${res.eventsReplayed ?? 0} 条`,
        )
        const warnings = res.warnings ?? []
        if (warnings.length > 0) {
          toast.info(`另有 ${warnings.length} 条迁移提示`, { description: warnings.join('\n') })
        }
      },
      onError: (err) => toast.error(`导入失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>备份导入 / 导出</h3>
        <span className="rank-note">旧版迁移格式 qimeng_backup.json</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <button className="pill" type="button" disabled={exportBackup.isPending} onClick={onExport}>
          {exportBackup.isPending ? '导出中…' : '导出全量备份'}
        </button>
        <span className="rank-note">当前库全量（文件清单/作者/标签/统计/收藏点赞），可回灌旧版 App 或其他实例</span>
      </div>
      <div
        className={`upload-drop${importBackup.isPending ? ' disabled' : ''}`}
        role="button"
        tabIndex={importBackup.isPending ? -1 : 0}
        aria-disabled={importBackup.isPending}
        aria-label="选择旧版备份 qimeng_backup.json 并导入恢复"
        style={{ marginTop: 12 }}
        onClick={() => {
          if (!importBackup.isPending) inputRef.current?.click()
        }}
        onKeyDown={(e) => {
          if (importBackup.isPending || (e.key !== 'Enter' && e.key !== ' ')) return
          e.preventDefault()
          inputRef.current?.click()
        }}
      >
        {importBackup.isPending ? (
          <b>导入中…</b>
        ) : (
          <>
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              style={{ width: 22, height: 22, display: 'block', margin: '0 auto 6px', color: 'var(--qm-primary)' }}
              aria-hidden="true"
            >
              <path d="M12 16V4" />
              <path d="m6 9 6-6 6 6" />
              <path d="M4 20h16" />
            </svg>
            <b>选择备份文件导入恢复</b>
            <div>点击选择旧版导出的 qimeng_backup.json，确认后按唯一键合并导入（不删除现有数据）</div>
          </>
        )}
      </div>
      <div className="rank-note" style={{ marginTop: 10, display: 'grid', gap: 4 }}>
        <span>· 导入幂等：同一备份重复导入不翻倍（作者/标签/关联按唯一键合并）</span>
        <span>· 统计/历史按事件回放重建；mediaFiles 仅在文件名能匹配到库内文件时建立关联</span>
        <span>· 扫描源等设备本机段不迁移，导入后请核对库注册与根路径</span>
      </div>
      <input
        ref={inputRef}
        type="file"
        accept=".json,application/json"
        hidden
        onChange={(e) => {
          void onPick(e)
        }}
        aria-label="选择旧版备份 JSON 文件"
      />
      <ConfirmDialog
        open={pending !== null}
        title={`导入备份「${pending?.summary.fileName ?? ''}」？`}
        description={pending ? backupSummaryText(pending.summary) : ''}
        confirmText="导入恢复"
        cancelText="取消"
        onConfirm={confirmImport}
        onOpenChange={(next) => {
          if (!next) setPending(null)
        }}
      />
    </div>
  )
}
