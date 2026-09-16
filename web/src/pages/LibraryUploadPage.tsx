import { useNavigate } from 'react-router'
import { DirBrowser } from '@/components/manage/DirBrowser'
import { UploadCard } from '@/components/manage/UploadCard'
import { Pill } from '@/components/ui/pill'
import { useLibraries } from '@/hooks/use-libraries'

/**
 * 上传子页（数据管理 hub「上传文件」卡 → /app/maintenance/files/upload）：
 * 目录浏览 + 上传卡原样搬移（W-1：选库 → 选目录 → 传文件，2026-09-17 hub
 * 拆分零改动）；libraries 与原页同源取数（useLibraries），传给 DirBrowser /
 * UploadCard 的方式不变。
 */
export default function LibraryUploadPage() {
  const navigate = useNavigate()
  const { data: libraries = [] } = useLibraries()

  return (
    <div className="page" id="page-maintenance-files-upload">
      <div className="settings-actions">
        <Pill onClick={() => navigate('/app/maintenance/files')}>← 返回数据管理</Pill>
      </div>
      <div className="page-head">
        <h2>上传文件</h2>
        <p>选择本地图片和视频上传到媒体库</p>
      </div>

      <DirBrowser libraries={libraries} />
      <UploadCard libraries={libraries} />
    </div>
  )
}
