import { AuthorMirrorCard } from '@/components/manage/AuthorMirrorCard'
import { SourceVocabularyCard } from '@/components/manage/SourceVocabularyCard'
import { ManagePageHeader } from '@/components/manage/ManagePageHeader'
import { ManageUnsavedGuard } from '@/components/manage/ManageUnsavedGuard'
import { useState } from 'react'

/**
 * 来源词表子页（数据管理 hub「来源词表」卡 → /app/maintenance/files/vocabulary）。
 * 2026-09-29 自上传文件页拆出（用户拍板「通用词表做一个单独的页面」——上传页
 * 只留上传动线，词表/镜像维护独立成页）：通用来源词表 + 作者总表镜像两卡
 * 整卡迁入，组件逻辑零改动只做信息架构拆分。两卡服务上传挂靠场景（词表供
 * 来源区快捷联想、镜像即挂靠作者清单真相），经 hub 入口卡进入。
 */
export default function LibraryVocabularyPage() {
  const [sourceDirty, setSourceDirty] = useState(false)
  const [mirrorDirty, setMirrorDirty] = useState(false)
  return (
    <div className="page manage-workspace" id="page-maintenance-files-vocabulary">
      <ManagePageHeader title="来源词表与镜像" description="来源词表用于获取渠道建议；镜像用于导出作者清单，两者分别保存" />

      <SourceVocabularyCard onDirtyChange={setSourceDirty} />
      <AuthorMirrorCard onDirtyChange={setMirrorDirty} />
      <ManageUnsavedGuard dirty={sourceDirty || mirrorDirty} />
    </div>
  )
}
