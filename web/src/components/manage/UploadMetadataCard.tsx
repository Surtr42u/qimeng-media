import { Link } from 'react-router'
import { ChevronDown } from 'lucide-react'
import type { UploadTarget } from '@/hooks/use-upload-target'
import { MAINTENANCE_FILES_VOCABULARY_PATH } from '@/lib/route-keys'
import { AuthorSuggestField } from './AuthorSuggestField'
import { SourceSelectField } from './SourceSelectField'

/** 可选挂靠不挡住主流程；折叠只隐藏编辑器，不清除本批次已经选定的值。 */
export function UploadMetadataCard({ target }: { target: UploadTarget }) {
  const hasAuthor = !!target.batchAuthor?.authorId
  return <details className="chart-card upload-metadata-card">
    <summary><span className="upload-step">02</span><span><strong>作者与来源</strong><small>{hasAuthor ? `${target.batchAuthor?.displayName ?? target.batchAuthor?.authorName ?? target.batchAuthor?.authorId} · ${target.batchSources.length} 个来源` : target.batchAuthor ? '作者待确认 · 请选择建议中的已有作者' : '可选 · 不指定作者也能上传'}</small></span><ChevronDown aria-hidden="true" /></summary>
    <div className="upload-metadata-body">
      <p className="rank-note">设置只用于之后添加的文件，已入队任务保持原关联。</p>
      <AuthorSuggestField avoidCollisions label="批次作者（可选，联想选择）" attachedHint="已选作者，上传成功后自动关联" value={target.batchAuthor} disabled={!target.enabled || !target.canAttach} onChange={target.onAuthorChange} />
      {target.canAttach && target.batchAuthor && !hasAuthor && <p className="upload-err" role="status">请从建议中选定已有作者，或清空作者后直接上传。</p>}
      <SourceSelectField label="批次来源（可选）" hint="选中的获取渠道会追加到该作者的来源记录；留空不改动。" disabledHint="先选定作者，再设置获取来源" disabled={!target.enabled || !target.canAttach || !hasAuthor} selected={target.batchSources} onChange={target.setBatchSources} />
      {!target.canAttach && <p className="rank-note">{target.enabled ? '当前库按目录或服务端规则关联作者，不支持手动挂靠。' : '选择支持作者关联的目标库后，可在这里设置。'}</p>}
      <Link className="manage-text-link" to={MAINTENANCE_FILES_VOCABULARY_PATH}>管理来源建议词与作者镜像</Link>
    </div>
  </details>
}
