import { useNavigate } from 'react-router'
import { BackupCard } from '@/components/manage/BackupCard'
import { Pill } from '@/components/ui/pill'

/**
 * 备份导入导出子页（数据管理 hub「备份导入导出」卡 →
 * /app/maintenance/files/backup）：BackupCard 原样搬移挂载（2026-09-17
 * hub 拆分零改动），卡片职责见组件自身注释。
 */
export default function BackupImportExportPage() {
  const navigate = useNavigate()

  return (
    <div className="page" id="page-maintenance-files-backup">
      <div className="settings-actions">
        <Pill onClick={() => navigate('/app/maintenance/files')}>← 返回数据管理</Pill>
      </div>
      <div className="page-head">
        <h2>备份导入导出</h2>
        <p>备份导入恢复与浏览数据同步</p>
      </div>

      <BackupCard />
    </div>
  )
}
