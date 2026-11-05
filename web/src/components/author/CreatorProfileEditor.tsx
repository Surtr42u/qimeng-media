import { useState } from 'react'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { batchFailureReason } from '@/lib/batch'
import type { useCreatorProfile } from '@/hooks/use-creator-profile'

export type CreatorProfileSection = 'x' | 'basic' | 'sources' | 'subscription' | 'review'
export const CREATOR_SECTION_TITLES = { x: 'X资料', basic: '基础资料', sources: '账号来源', subscription: '作品订阅', review: '候选核对' } as const

type ProfileState = ReturnType<typeof useCreatorProfile>

/** 每次编辑一个资料块，完整草稿仍由既有hook持有。 */
export function CreatorProfileEditor({ state, section, name, avatar, onClose, onSaved }: {
  state: ProfileState; section: CreatorProfileSection; name: string; avatar?: string; onClose: () => void; onSaved: () => void
}) {
  const [editChinese, setEditChinese] = useState(false)
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
      <div className="creator-editor-person">
        <div className="creator-profile-avatar">{avatar ? <img src={avatar} alt="" /> : Array.from(name)[0]}</div>
        <div><p>{name}</p><h3>编辑{CREATOR_SECTION_TITLES[section]}</h3></div>
      </div>
      <button type="button" className="pill" onClick={close} disabled={busy}>返回概览</button>
    </div>
    <form onSubmit={event => { event.preventDefault(); state.save.mutate(form, { onSuccess: onSaved }) }}>
      <fieldset className="creator-edit-fields" disabled={busy}>
        <legend>{CREATOR_SECTION_TITLES[section]}</legend>
        {section === 'x' && <>
          <label className="settings-field">
            <span>X 账号主页</span>
            <input type="url" placeholder="https://x.com/账号名" value={form.xProfile?.url ?? ''}
              onChange={e => state.change('xProfile', { ...form.xProfile, url: e.target.value, biography: form.xProfile?.biography ?? '' })} />
          </label>
          <div className="creator-edit-language">
            <div className="creator-language" role="group" aria-label="简介编辑语言">
              <button type="button" aria-pressed={!editChinese} onClick={() => setEditChinese(false)}>原文</button>
              <button type="button" aria-pressed={editChinese} onClick={() => setEditChinese(true)}>中文</button>
            </div>
            <span>分别保存原文与译文</span>
          </div>
          <label className="settings-field">
            <span>{editChinese ? '中文译文' : 'X 简介原文'}</span>
            <textarea rows={6} aria-label={editChinese ? '中文译文' : 'X 简介原文'} maxLength={editChinese ? 2000 : 1000}
              placeholder={editChinese ? '填写或校对中文翻译…' : '粘贴作者的X简介原文…'}
              value={(editChinese ? form.xProfile?.biographyZh : form.xProfile?.biography) ?? ''}
              onChange={e => state.change('xProfile', editChinese
                ? { url: form.xProfile?.url ?? '', biography: form.xProfile?.biography ?? '', biographyZh: e.target.value }
                : { url: form.xProfile?.url ?? '', biography: e.target.value, biographyZh: '' })} />
            <small>{Array.from((editChinese ? form.xProfile?.biographyZh : form.xProfile?.biography) ?? '').length} / {editChinese ? 2000 : 1000}</small>
          </label>
          <p className="creator-profile-note">修改原文后需重新校对中文译文，旧译文会清空。语言切换保留当前草稿。</p>
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
        <span>{busy ? '正在保存…' : state.dirty ? '有未保存的修改' : '已与当前资料一致'}</span>
        <div className="creator-editor-buttons">
          <button type="button" className="pill" onClick={close} disabled={busy}>取消</button>
          <button type="submit" className="save-btn" disabled={busy || !state.dirty}>{state.save.isPending ? '保存中…' : '保存资料'}</button>
        </div>
      </div>
    </form>
    <ConfirmDialog open={confirmDiscard} title="放弃未保存修改？" description="这组资料仍有草稿。" confirmText="放弃修改" cancelText="继续编辑" onConfirm={onClose} onOpenChange={setConfirmDiscard} />
  </section>
}
