import { DirBrowser } from '@/components/manage/DirBrowser'
import { ManagePageHeader, ManageQueryError } from '@/components/manage/ManagePageHeader'
import { useLibraries } from '@/hooks/use-libraries'

/** 整理已入库文件独立于新文件上传，避免两套目录选择器被误当成同一目标。 */
export default function LibraryFilesPage() {
  const query = useLibraries()
  return <div className="page manage-workspace" id="page-maintenance-files-browse">
    <ManagePageHeader title="文件整理" description="浏览已入库目录；重命名与移动保留关联，删除先进入回收站" />
    {query.isError && <ManageQueryError retry={() => { void query.refetch() }}>媒体库读取失败，已有文件不会因此消失。</ManageQueryError>}
    {query.isLoading ? <div className="settings-card"><p className="rank-note">正在读取媒体库…</p></div> : <DirBrowser libraries={query.data ?? []} />}
  </div>
}
