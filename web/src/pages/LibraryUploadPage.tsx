import { Link } from 'react-router'
import { ManagePageHeader, ManageQueryError } from '@/components/manage/ManagePageHeader'
import { ServerImportPanel } from '@/components/manage/ServerImportPanel'
import { UploadWorkbench } from '@/components/manage/UploadWorkbench'
import { useLibraries } from '@/hooks/use-libraries'
import { MAINTENANCE_FILES_BROWSER_PATH, MAINTENANCE_FILES_VOCABULARY_PATH } from '@/lib/route-keys'

export default function LibraryUploadPage() {
  const query = useLibraries()
  return <div className="page manage-workspace" id="page-maintenance-files-upload">
    <ManagePageHeader title="上传文件" description="先确认目标，再设置作者与来源；选择文件后立即上传" actions={<Link className="manage-text-link" to={MAINTENANCE_FILES_BROWSER_PATH}>整理已入库文件 →</Link>} />
    {query.isError && <ManageQueryError retry={() => { void query.refetch() }} busy={query.isFetching}>目标库列表读取失败，新文件暂不入队；正在进行的上传继续。</ManageQueryError>}
    <UploadWorkbench libraries={query.data ?? []} targetsReady={!query.isError && query.data !== undefined} />
    <p className="manage-helper">需要修改建议词或作者清单镜像？<Link to={MAINTENANCE_FILES_VOCABULARY_PATH}>来源词表与镜像</Link></p>
    <ServerImportPanel />
  </div>
}
