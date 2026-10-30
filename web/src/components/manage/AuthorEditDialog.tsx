import { useState } from 'react'
import { Dialog } from 'radix-ui'
import { toast } from 'sonner'
import type { Author } from '@/api/generated'
import { useAuthorSourcesById, useSaveAuthorSources } from '@/hooks/use-authors'
import { SourceSelectField } from './SourceSelectField'
import { ManageUnsavedGuard } from './ManageUnsavedGuard'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { batchFailureReason } from '@/lib/batch'
import { truncateToRuneMax } from '@/lib/vocabulary-edit'

export function AuthorEditDialog({ author, busy, onConfirm, onDismiss }: {
  author?: Author; busy: boolean; onConfirm: (name: string) => void; onDismiss: () => void
}) {
  const [name, setName] = useState(author?.displayName ?? '')
  const sources = useAuthorSourcesById(author?.id)
  const saveSources = useSaveAuthorSources()
  const [draft, setDraft] = useState<string[] | null>(null)
  const [discard, setDiscard] = useState(false)
  const [tab, setTab] = useState<'name' | 'sources'>('name')
  const pending = busy || saveSources.isPending
  const dirty = name !== (author?.displayName ?? '') || (draft !== null && JSON.stringify(draft) !== JSON.stringify(sources.data?.sources ?? []))
  const dismiss = () => { if (!pending) { if (dirty) setDiscard(true); else onDismiss() } }
  return <>
    <ManageUnsavedGuard dirty={dirty || pending} description="作者资料尚未保存，离开将丢弃本次修改。" />
    <Dialog.Root open onOpenChange={open => { if (!open) dismiss() }}>
      <Dialog.Portal>
        <Dialog.Overlay className="detail-dialog-overlay" />
        <Dialog.Content className="detail-dialog author-editor" onEscapeKeyDown={e => { e.preventDefault(); dismiss() }} onPointerDownOutside={e => { e.preventDefault(); dismiss() }}>
          <Dialog.Title className="detail-dialog-title">{author ? '编辑作者资料' : '新建作者'}</Dialog.Title>
          <Dialog.Description className="detail-dialog-desc">{author?.displayName ?? '添加作者后，可继续维护来源和关联文件。'}</Dialog.Description>
          {author && <div className="settings-options" aria-label="资料分类">
            <button className={`pill${dirty && <p className="detail-dialog-desc">请保存当前修改后切换分类，或关闭并放弃修改。</p>}
          {tab === 'name' ? ' active' : ''}`} disabled={dirty || pending} onClick={() => setTab('name')} aria-pressed={tab === 'name'}>名称与别名</button>
            <button className={`pill${tab === 'sources' ? ' active' : ''}`} disabled={dirty || pending} onClick={() => setTab('sources')} aria-pressed={tab === 'sources'}>来源 / 出处</button>
          </div>}
          {dirty && <p className="detail-dialog-desc">请保存当前修改后切换分类，或关闭并放弃修改。</p>}
          {tab === 'name' ? <div className="settings-field">
            <label htmlFor="author-name">作者名称</label>
            <input id="author-name" className="tag-input" value={name} disabled={pending} onChange={e => setName(truncateToRuneMax(e.target.value, 200))} />
            <small>多个别名用「 / 」分隔；改名会保留关注与文件关联。</small>
            <small>{Array.from(name).length} / 200</small>
            <button className="confirm-btn confirm-btn--primary" disabled={pending || !name.trim() || (!!author && name === author.displayName)} onClick={() => onConfirm(name)}>{busy ? '保存中…' : '保存名称'}</button>
          </div> : <div>
            {sources.isPending ? <p role="status">正在读取来源…</p> : sources.isError ? <p role="alert">读取失败 <button className="pill" onClick={() => void sources.refetch()}>重试</button></p> : <>
              <SourceSelectField selected={draft ?? sources.data?.sources ?? []} onChange={setDraft} disabled={pending} label="来源 / 出处" hint="记录获取渠道、平台或个人链接，不参与作品匹配。移除全部并保存将清空记录。输入后按回车加入。" />
              <button className="confirm-btn confirm-btn--primary" disabled={pending || draft === null} onClick={() => {
                if (!author?.id || draft === null) return
                saveSources.mutate({ authorId: author.id, sources: draft }, { onSuccess: () => { setDraft(null); toast.success('来源已保存') }, onError: err => toast.error(batchFailureReason(err)) })
              }}>{saveSources.isPending ? '保存中…' : '保存来源'}</button>
            </>}
          </div>}
          <div className="detail-dialog-foot"><button className="confirm-btn confirm-btn--cancel" disabled={pending} onClick={dismiss}>关闭</button></div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
    <ConfirmDialog open={discard} title="放弃未保存的修改？" description="本次尚未保存的名称或来源将丢弃。" confirmText="放弃修改" cancelText="继续编辑" onConfirm={onDismiss} onOpenChange={setDiscard} />
  </>
}
