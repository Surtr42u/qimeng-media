import { BackupCard } from '@/components/manage/BackupCard'
import { ManagePageHeader } from '@/components/manage/ManagePageHeader'

/**
 * 备份导入导出子页（数据管理 hub「备份导入导出」卡 →
 * /app/maintenance/files/backup）：BackupCard 原样搬移挂载（2026-09-17
 * hub 拆分零改动），卡片职责见组件自身注释。
 */
export default function BackupImportExportPage() {
  return (
    <div className="page manage-workspace" id="page-maintenance-files-backup">
      <ManagePageHeader title="备份导入导出" description="先传媒体原件，再合并当前 v2 的记录与关联；备份文件不包含媒体本体" />

      <BackupCard />
    </div>
  )
}
