import { TxtAuthorImportCard } from '@/components/manage/TxtAuthorImportCard'
import { ManagePageHeader } from '@/components/manage/ManagePageHeader'

/**
 * 作者 TXT 导入子页（数据管理 hub「作者 TXT 导入」卡 →
 * /app/maintenance/files/authors-txt）：TxtAuthorImportCard 原样搬移挂载
 * （2026-09-17 hub 拆分零改动），卡片职责见组件自身注释。
 */
export default function AuthorTxtImportPage() {
  return (
    <div className="page manage-workspace" id="page-maintenance-files-authors-txt">
      <ManagePageHeader title="作者 TXT 导入" description="导入作者文件清单、核对冲突，再重建作者与媒体的匹配关系" />

      <TxtAuthorImportCard />
    </div>
  )
}
