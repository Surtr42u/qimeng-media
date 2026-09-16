import { useNavigate } from 'react-router'
import { TxtAuthorImportCard } from '@/components/manage/TxtAuthorImportCard'
import { Pill } from '@/components/ui/pill'

/**
 * 作者 TXT 导入子页（数据管理 hub「作者 TXT 导入」卡 →
 * /app/maintenance/files/authors-txt）：TxtAuthorImportCard 原样搬移挂载
 * （2026-09-17 hub 拆分零改动），卡片职责见组件自身注释。
 */
export default function AuthorTxtImportPage() {
  const navigate = useNavigate()

  return (
    <div className="page" id="page-maintenance-files-authors-txt">
      <div className="settings-actions">
        <Pill onClick={() => navigate('/app/maintenance/files')}>← 返回数据管理</Pill>
      </div>
      <div className="page-head">
        <h2>作者 TXT 导入</h2>
        <p>导入旧项目作者清单并重建关联</p>
      </div>

      <TxtAuthorImportCard />
    </div>
  )
}
