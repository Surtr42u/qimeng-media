import { useNavigate } from 'react-router'
import { AuthorMirrorCard } from '@/components/manage/AuthorMirrorCard'
import { DirBrowser } from '@/components/manage/DirBrowser'
import { SourceVocabularyCard } from '@/components/manage/SourceVocabularyCard'
import { UploadWorkbench } from '@/components/manage/UploadWorkbench'
import { Pill } from '@/components/ui/pill'
import { useLibraries } from '@/hooks/use-libraries'
import { MAINTENANCE_FILES_PATH } from '@/lib/route-keys'

/**
 * 上传子页（数据管理 hub「上传文件」卡 → /app/maintenance/files/upload）。
 * 2026-09-28 重排：UploadCard 升级为 UploadWorkbench（交互同构 App 上传页：
 * 批次库/目录 + 批次挂靠默认 + 暂存列表逐项编辑 + 门禁上传递，三段常驻显示）；
 * 通用来源词表与作者总表镜像两卡自设置页迁入（词表/镜像服务上传挂靠场景，
 * 与上传工作台同页维护），目录浏览卡保留在后（文件整理动线，与上传无耦合）。
 * libraries 同源取数（useLibraries），传给各卡的方式不变。
 */
export default function LibraryUploadPage() {
  const navigate = useNavigate()
  const { data: libraries = [] } = useLibraries()

  return (
    <div className="page" id="page-maintenance-files-upload">
      <div className="settings-actions">
        <Pill onClick={() => navigate(MAINTENANCE_FILES_PATH)}>← 返回数据管理</Pill>
      </div>
      <div className="page-head">
        <h2>上传文件</h2>
        <p>选择本地图片和视频上传到媒体库，可指定作者与来源自动挂靠</p>
      </div>

      <UploadWorkbench libraries={libraries} />
      <DirBrowser libraries={libraries} />
      <SourceVocabularyCard />
      <AuthorMirrorCard />
    </div>
  )
}
