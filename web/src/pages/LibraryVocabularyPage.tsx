import { useNavigate } from 'react-router'
import { AuthorMirrorCard } from '@/components/manage/AuthorMirrorCard'
import { SourceVocabularyCard } from '@/components/manage/SourceVocabularyCard'
import { Pill } from '@/components/ui/pill'
import { MAINTENANCE_FILES_PATH } from '@/lib/route-keys'

/**
 * 来源词表子页（数据管理 hub「来源词表」卡 → /app/maintenance/files/vocabulary）。
 * 2026-09-29 自上传文件页拆出（用户拍板「通用词表做一个单独的页面」——上传页
 * 只留上传动线，词表/镜像维护独立成页）：通用来源词表 + 作者总表镜像两卡
 * 整卡迁入，组件逻辑零改动只做信息架构拆分。两卡服务上传挂靠场景（词表供
 * 来源区快捷联想、镜像即挂靠作者清单真相），经 hub 入口卡进入。
 */
export default function LibraryVocabularyPage() {
  const navigate = useNavigate()

  return (
    <div className="page" id="page-maintenance-files-vocabulary">
      <div className="settings-actions">
        <Pill onClick={() => navigate(MAINTENANCE_FILES_PATH)}>← 返回数据管理</Pill>
      </div>
      <div className="page-head">
        <h2>来源词表</h2>
        <p>通用来源建议词与作者总表镜像维护，服务上传挂靠与资产编辑来源区</p>
      </div>

      <SourceVocabularyCard />
      <AuthorMirrorCard />
    </div>
  )
}
