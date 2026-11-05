import { useState } from 'react'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { batchFailureReason } from '@/lib/batch'
import type { useCreatorProfile } from '@/hooks/use-creator-profile'

export type CreatorProfileSection = 'x' | 'basic' | 'sources' | 'subscription' | 'review'
export const CREATOR_SECTION_TITLES = { x: 'X资料', basic: '基础资料', sources: '账号来源', subscription: '作品订阅', review: '候选核对' } as const

type ProfileState = ReturnType<typeof useCreatorProfile>

/** 每次编辑一个资料块，完整草稿仍由既有hook持有。 */
export function CreatorProfileEditor({ state, section, onClose, onSaved }: {
  state: ProfileState; section: CreatorProfileSection; onClose: () => void; onSaved: () => void
}) {
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const { form, busy } = state
  if (!form) return null
  const close = () => {
    if (busy) return
    if (state.dirty) setConfirmDiscard(true)
    else onClose()
  }
  const basicFields = <>
    <label className="settings-field"><span>公开昵称</span>
      <input value={form.nickname} maxLength={200} onChange={e => state.change('nickname', e.target.value)} />
    </label>
    <label className="settings-field"><span>整理简介</span>
      <textarea rows={5} maxLength={2000} value={form.biography} onChange={e => state.change('biography', e.target.value)} />
    </label>
    <label className="settings-field"><span>头像来源</span>
      <input type="url" placeholder="https://…" value={form.avatarUrl} onChange={e => state.change('avatarUrl', e.target.value)} />
    </label>
  </>
  const sourceFields = <>
    <label className="settings-field"><span>创作者主页</span>
      <input type="url" placeholder="https://…" value={form.homepageUrl} onChange={e => state.change('homepageUrl', e.target.value)} />
    </label>
    <label className="settings-field"><span>核验链接（每行一个）</span>
      <textarea rows={4} value={form.evidenceUrls.join('\n')} onChange={e => state.change('evidenceUrls', e.target.value.split('\n'))} />
    </label>
  </>
  return <section className="creator-editor" aria-label={`编辑${CREATOR_SECTION_TITLES[section]}`}>
    <div className="creator-editor-heading">
      <div><h3>{CREATOR_SECTION_TITLES[section]}</h3><p>修改后保存，返回资料概览。</p></div>
      <button type="button" className="pill" onClick={close} disabled={busy}>返回概览</button>
    </div>
    <form onSubmit={event => { event.preventDefault(); state.save.mutate(form, { onSuccess: onSaved }) }}>
      <fieldset disabled={busy}>
        {section === 'x' && <>
          <label className="settings-field">
            <span>X 账号主页</span>
            <input type="url" placeholder="https://x.com/账号名" value={form.xProfile?.url ?? ''}
              onChange={e => state.change('xProfile', { url: e.target.value, biography: form.xProfile?.biography ?? '' })} />
          </label>
          <label className="settings-field">
            <span>X 简介原文</span>
            <textarea rows={5} maxLength={1000} value={form.xProfile?.biography ?? ''}
              onChange={e => state.change('xProfile', { url: form.xProfile?.url ?? '', biography: e.target.value })} />
          </label>
          <p className="creator-profile-note">简介原文显示在作者主页。</p>
        </>}
        {(section === 'basic' || section === 'review') && basicFields}
        {(section === 'sources' || section === 'review') && sourceFields}
        {section === 'sources' && <>
          <label className="settings-field">
            <span>身份状态</span>
            <select value={form.status} onChange={e => state.change('status', e.target.value as typeof form.status)}>
              <option value="empty">待完善</option>
              <option value="candidate">待核实</option>
              <option value="verified">已确认</option>
            </select>
          </label>
          <label className="settings-switch">
            <input type="checkbox" checked={form.autoRefresh}
              disabled={form.status !== 'verified' || !form.homepageUrl.trim().startsWith('https://')}
              onChange={e => state.change('autoRefresh', e.target.checked)} />
            自动检查公开资料
          </label>
          <p className="creator-profile-note">检查变更会进入待审候选。</p>
        </>}
        {section === 'subscription' && <>
          <label className="settings-field">
            <span>作品发布订阅地址（RSS / Atom）</span>
            <input type="url" placeholder="https://…" value={form.feedUrl} onChange={e => state.change('feedUrl', e.target.value)} />
          </label>
          <label className="settings-switch">
            <input type="checkbox" checked={form.trackUpdates}
              disabled={form.status !== 'verified' || !form.feedUrl.trim().startsWith('https://')}
              onChange={e => state.change('trackUpdates', e.target.checked)} />
            检查作品订阅更新
          </label>
          <p className="creator-profile-note">用于作品发布专用订阅。未配置时，可从作者主页打开发布入口。</p>
        </>}
        {section === 'review' && <p className="creator-profile-note">核对候选中的简介、头像及主页来源后保存。</p>}
      </fieldset>
      <div className="creator-editor-footer">
        {state.save.isError && <p role="alert">保存失败，草稿已保留：{batchFailureReason(state.save.error)}</p>}
        <span>{busy ? '正在保存…' : state.dirty ? '有未保存的修改' : '修改后统一保存'}</span>
        <div className="creator-editor-buttons">
          <button type="button" className="pill" onClick={close} disabled={busy}>取消</button>
          <button type="submit" className="save-btn" disabled={busy}>{state.save.isPending ? '保存中…' : '保存资料'}</button>
        </div>
      </div>
    </form>
    <ConfirmDialog open={confirmDiscard} title="放弃未保存修改？" description="这组资料仍有草稿。" confirmText="放弃修改" cancelText="继续编辑" onConfirm={onClose} onOpenChange={setConfirmDiscard} />
  </section>
}
