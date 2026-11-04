import type { Author } from '@/api/generated'
import { useCreatorAvatar, useCreatorProfile } from '@/hooks/use-creator-profile'
import { useToggleFollow } from '@/hooks/use-authors'
import { batchFailureReason } from '@/lib/batch'
import { ManageUnsavedGuard } from '@/components/manage/ManageUnsavedGuard'

function PublicLink({ url, label }: { url: string; label?: string }) {
  // 不让旧缓存或导入载荷把可执行 scheme 带入链接。
  if (!url.startsWith('https://')) return <span>{label ?? url}</span>
  return <a href={url} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{label ?? url}</a>
}

/** 原作者作品页的概要与维护入口，普通/COS共享，作品筛选和网格仍由集合页持有。 */
export function CreatorProfileCard({ author }: { author: Author & { id: string } }) {
  const state = useCreatorProfile(author.id)
  const { profile, form, busy } = state
  const avatar = useCreatorAvatar(author.id, profile?.avatarUrl ?? '', profile?.revision ?? 0)
  const follow = useToggleFollow()
  return <section className="rank-card creator-profile" aria-label="作者档案">
    <div className="creator-profile-identity">
      <div className="creator-profile-avatar">{avatar ? <img src={avatar} alt="" /> : (author.displayName ?? '?').slice(0, 1)}</div>
      <div className="creator-profile-name"><h2>{author.displayName}</h2>{profile?.nickname && <span>{profile.nickname}</span>}<p>{author.type === 'cos' ? 'COS 作者' : '创作者'} · {author.fileCount ?? '—'} 个本地文件</p></div>
      <button type="button" className="pill" disabled={follow.isPending} onClick={() => follow.mutate({ authorId: author.id, follow: !author.followed })}>{author.followed ? '已关注' : '关注'}</button>
    </div>
    {follow.isError && <p role="alert">关注操作失败：{batchFailureReason(follow.error)}</p>}
    {profile?.biography && <p className="creator-profile-bio">{profile.biography}</p>}
    {profile?.homepageUrl && <PublicLink url={profile.homepageUrl} label="创作者主页 ↗" />}
    {state.isPending && <p role="status">正在读取作者资料…</p>}
    {state.isError && <div role="alert"><p>作者资料读取失败，请确认服务端已更新：{batchFailureReason(state.error)}</p><button type="button" className="pill" onClick={() => { void state.refetch() }}>重试资料</button></div>}
    {profile && form && <>
      <ManageUnsavedGuard dirty={state.dirty || busy} description="作者档案仍有未保存的修改或正在维护。" />
      <div className="creator-profile-status"><span>{profile.status === 'verified' ? '身份已确认' : profile.status === 'candidate' ? '资料待核实' : '资料待完善'}</span>{profile.lastSuccessAt && <span>最近成功：{profile.lastSuccessAt}</span>}</div>
      {profile.lastError && <p role="alert">上次维护未完成：{profile.lastError}</p>}
      <details className="creator-maintenance"><summary>资料与维护</summary>
        <form onSubmit={event => { event.preventDefault(); state.save.mutate(form) }}>
          <fieldset disabled={busy}>
            <legend>公开作者档案</legend>
            <div className="settings-grid">
              <label className="settings-field"><span>公开昵称</span><input value={form.nickname} maxLength={200} onChange={e => state.change('nickname', e.target.value)} /></label>
              <label className="settings-field"><span>创作者主页</span><input type="url" placeholder="https://…" value={form.homepageUrl} onChange={e => state.change('homepageUrl', e.target.value)} /></label>
            </div>
            <label className="settings-field"><span>简介</span><textarea rows={3} maxLength={2000} value={form.biography} onChange={e => state.change('biography', e.target.value)} /></label>
            <label className="settings-field"><span>头像来源</span><input type="url" placeholder="https://…" value={form.avatarUrl} onChange={e => state.change('avatarUrl', e.target.value)} /></label>
            <label className="settings-field"><span>身份证据链接（每行一个）</span><textarea rows={2} value={form.evidenceUrls.join('\n')} onChange={e => state.change('evidenceUrls', e.target.value.split('\n'))} /></label>
            <label className="settings-field"><span>身份状态</span><select value={form.status} onChange={e => state.change('status', e.target.value as typeof form.status)}><option value="empty">待完善</option><option value="candidate">待核实</option><option value="verified">已确认</option></select></label>
            <label className="settings-switch"><input type="checkbox" checked={form.autoRefresh} disabled={form.status !== 'verified' || !form.homepageUrl.trim().startsWith('https://')} onChange={e => state.change('autoRefresh', e.target.checked)} />自动检查公开资料</label>
            <p className="creator-profile-note">自动检查须先确认身份及主页。刷新生成候选资料，保留已保存的人工内容。</p>
            <label className="settings-field"><span>公开动态订阅地址（RSS / Atom）</span><input type="url" placeholder="https://…" value={form.feedUrl} onChange={e => state.change('feedUrl', e.target.value)} /></label>
            <label className="settings-switch"><input type="checkbox" checked={form.trackUpdates} disabled={form.status !== 'verified' || !form.feedUrl.trim().startsWith('https://')} onChange={e => state.change('trackUpdates', e.target.checked)} />检查公开新动态</label>
            <p className="creator-profile-note">需要有效的公开订阅源；不自动下载作品。不支持的来源会明确报告失败。</p>
            <div className="settings-actions"><button type="submit" className="save-btn">{state.save.isPending ? '保存中…' : '保存作者资料'}</button><button type="button" className="pill" disabled={state.dirty} onClick={() => state.refresh.mutate()}>{state.refresh.isPending ? '检查中…' : '检查公开资料'}</button>{state.dirty && <button type="button" className="pill" onClick={state.discard}>放弃修改</button>}</div>
          </fieldset>
        </form>
        {state.save.isError && <p role="alert">保存失败，草稿已保留：{batchFailureReason(state.save.error)}</p>}
        {state.refresh.isError && <p role="alert">检查失败：{batchFailureReason(state.refresh.error)}</p>}
        {state.hasCandidate && <section className="creator-candidate" aria-label="待审核资料"><h3>候选资料</h3><p>请核对身份、资料与证据，再将候选填入草稿；保存后生效。</p><dl><dt>昵称</dt><dd>{profile.candidate.nickname || '—'}</dd><dt>简介</dt><dd>{profile.candidate.biography || '—'}</dd><dt>主页</dt><dd><PublicLink url={profile.candidate.homepageUrl} /></dd><dt>头像来源</dt><dd><PublicLink url={profile.candidate.avatarUrl} /></dd></dl><ul>{profile.candidate.evidenceUrls.map(url => <li key={url}><PublicLink url={url} /></li>)}</ul><button type="button" className="pill" disabled={busy || state.dirty} onClick={state.applyCandidate}>填入候选草稿，继续核对</button></section>}
      </details>
      {profile.trackUpdates && <section className="creator-updates" aria-label="公开动态"><h3>公开动态</h3>{profile.updates.length ? <ul>{profile.updates.map(update => <li key={update.id}><PublicLink url={update.url} label={update.title} /><span>{update.publishedAt}</span></li>)}</ul> : <p>{profile.lastError ? '检查未完成，暂无可展示记录' : profile.lastCheckedAt ? '暂无已获取的公开动态' : '尚未检查公开动态'}</p>}</section>}
    </>}
  </section>
}
