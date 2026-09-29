import { useState } from 'react'
import { toast } from 'sonner'
import type { AuthorMirrorConfig } from '@/api/generated'
import { useAuthorMirror, useSaveAuthorMirror } from '@/hooks/use-authors'
import { batchFailureReason } from '@/lib/batch'

/**
 * 作者总表镜像卡（REQ-上传指定作者与来源 §3.4 自动镜像；2026-09-28 自设置页
 * 迁入文件管理域——镜像内容即上传挂靠落库的作者清单真相，与上传工作台/词表
 * 同页维护）：GET /authors/mirror 回填 + 本地草稿，保存一次 PUT 两字段
 * （path 空=关闭）；保存成功即触发一次镜像刷新（服务端尽力而为）。
 */
export function AuthorMirrorCard() {
  const { data: mirrorData } = useAuthorMirror()
  const saveMirror = useSaveAuthorMirror()
  const [mirrorDraft, setMirrorDraft] = useState<AuthorMirrorConfig | null>(null)
  const mirror: AuthorMirrorConfig = mirrorDraft ?? {
    path: mirrorData?.path ?? '',
    fragmentFilename: mirrorData?.fragmentFilename ?? '',
  }

  const setMirrorField = (key: keyof AuthorMirrorConfig, raw: string): void => {
    setMirrorDraft({ ...mirror, [key]: raw })
  }

  const doSaveMirror = (): void => {
    saveMirror.mutate(
      { path: mirror.path ?? '', fragmentFilename: mirror.fragmentFilename ?? '' },
      {
        onSuccess: () => {
          setMirrorDraft(null)
          // 保存成功即尝试一次镜像刷新（服务端尽力而为）
          toast.success('镜像配置已保存')
        },
        onError: (err) =>
          // 4xx 时 unwrapSdkResult 抛的是 {code,message,status} 错误体（非 Error
          // 实例），弱模式会落成 "[object Object]"——统一走 batchFailureReason 提取
          toast.error(`镜像配置保存失败：${batchFailureReason(err)}`),
      },
    )
  }

  return (
    <div className="settings-card">
      <h3>作者总表镜像</h3>
      <p>服务端把作者总表片段自动镜像到该路径 · 保存即生效</p>
      <div className="settings-grid">
        <label className="settings-field">
          <span>镜像文件路径</span>
          <input
            type="text"
            placeholder="服务端可写的绝对路径；留空 = 关闭"
            autoComplete="off"
            value={mirror.path ?? ''}
            onChange={(e) => setMirrorField('path', e.target.value)}
          />
          <small>须为服务端文件系统内的绝对路径（相对路径被拒绝）；留空 = 关闭镜像</small>
        </label>
        <label className="settings-field">
          <span>镜像目标片段</span>
          <input
            type="text"
            placeholder="留空=最近导入的片段"
            autoComplete="off"
            value={mirror.fragmentFilename ?? ''}
            onChange={(e) => setMirrorField('fragmentFilename', e.target.value)}
          />
          <small>多片段场景指明镜像哪份片段</small>
        </label>
      </div>
      {/* 单向镜像口径必须向用户言明（REQ §3.4）：本地手改会被覆盖 */}
      <p className="rank-note" style={{ marginTop: 10 }}>
        单向同步：本地手改该文件会在下次服务端变更时被覆盖——需要手工编辑清单时，复制一份改完再导入。
      </p>
      <div className="settings-actions" style={{ marginTop: 12 }}>
        <button
          className="save-btn"
          type="button"
          disabled={saveMirror.isPending}
          onClick={doSaveMirror}
        >
          {saveMirror.isPending ? '保存中…' : '保存镜像配置'}
        </button>
      </div>
    </div>
  )
}
