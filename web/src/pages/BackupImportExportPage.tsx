import { useSearchParams, Link } from 'react-router'
import { BackupCard } from '@/components/manage/BackupCard'
import { TxtAuthorImportCard } from '@/components/manage/TxtAuthorImportCard'
import { MaintenanceFolderCard } from '@/components/manage/MaintenanceFolderCard'
import { ManagePageHeader } from '@/components/manage/ManagePageHeader'
import { useMaintenanceFolder } from '@/hooks/use-maintenance-folder'
import { MAINTENANCE_UPLOAD_PATH } from '@/lib/route-keys'

export default function BackupImportExportPage() {
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'backup'
  const { root, loading } = useMaintenanceFolder()
  return <div className="page manage-workspace" id="page-maintenance-files-backup">
    <ManagePageHeader title="资料备份与归档" description="一个目录管理作者资料、完整备份和上传归档" />
    <MaintenanceFolderCard />
    <nav className="maintenance-tabs" aria-label="资料维护任务">
      {[['backup', '备份恢复'], ['authors', '作者TXT'], ['archive', '上传归档']].map(([value, label]) =>
        <button className="pill" type="button" key={value} aria-pressed={tab === value} onClick={() => setParams({ tab: value }, { replace: true })}>{label}</button>)}
    </nav>
    {tab === 'authors' ? <TxtAuthorImportCard folderMode={root !== null} /> : tab === 'archive' ?
      <section className="rank-card"><h3>上传归档</h3>
        <p>{root ? `${root.name} / 上传归档` : '选择维护根目录后启用上传原件副本归档。'}</p>
        <p className="rank-note">按库及目标目录归档。同名文件保留并提示，归档失败会单独显示在上传任务中。浏览器选择的原文件继续保留。</p>
        <Link className="pill" to={MAINTENANCE_UPLOAD_PATH}>前往上传文件</Link>
      </section> : <BackupCard folderMode={root !== null} directoryLoading={loading} />}
  </div>
}
