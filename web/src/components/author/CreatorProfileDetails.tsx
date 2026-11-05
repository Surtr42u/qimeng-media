import { useState, type ReactNode } from 'react'
import type { Author } from '@/api/generated'
import { useCreatorAvatar, useCreatorProfile } from '@/hooks/use-creator-profile'
import { batchFailureReason } from '@/lib/batch'
import { ManageUnsavedGuard } from '@/components/manage/ManageUnsavedGuard'
import { CreatorPublicLink } from './CreatorPublicLink'
import { CreatorProfileEditor, CREATOR_SECTION_TITLES, type CreatorProfileSection } from './CreatorProfileEditor'

function ProfileGroup({ title, busy, onEdit, children }: {
  title: string; busy: boolean; onEdit: () => void; children: ReactNode
}) {
  return <section className="creator-readonly-group">
    <div className="creator-group-heading">
      <h3>{title}</h3>
      <button type="button" className="pill" disabled={busy} onClick={onEdit} aria-label={`编辑${title}`}>编辑</button>
    </div>
    {children}
  </section>
}

/** 概览以只读卡片呈现，进入指定资料块后才出现输入控件。 */
export function CreatorProfileDetails({ author }: { author: Author & { id: string } }) {
  const state = useCreatorProfile(author.id)
  const { profile, busy } = state
  const [editing, setEditing] = useState<CreatorProfileSection | null>(null)
  const avatar = useCreatorAvatar(author.id, profile?.avatarUrl ?? '', profile?.revision ?? 0)
  const closeEditor = () => { state.discard(); setEditing(null) }

  return <section className="rank-card creator-profile creator-details" aria-label="作者档案">
    <ManageUnsavedGuard dirty={state.dirty || busy} description="作者资料仍有未保存的修改或正在保存。" />
    {editing ? <CreatorProfileEditor state={state} section={editing} onClose={closeEditor} onSaved={() => setEditing(null)} /> : <>
      <div className="creator-profile-identity">
        <div className="creator-profile-avatar">{avatar ? <img src={avatar} alt="" /> : Array.from(author.displayName ?? '?')[0]}</div>
        <div className="creator-profile-name">
          <h2>{author.displayName}</h2>
          <p>{profile?.status === 'verified' ? '身份已确认' : profile?.status === 'candidate' ? '资料待核实' : '资料待完善'}</p>
        </div>
      </div>
      <h3 className="creator-details-title">资料与维护</h3>
      {state.isPending && <p role="status">正在读取作者资料…</p>}
      {state.isError && <div role="alert">
        资料读取失败：{batchFailureReason(state.error)}
        <button className="pill" onClick={() => void state.refetch()}>重试</button>
      </div>}
      {profile && <div className="creator-readonly-grid">
        <ProfileGroup title={CREATOR_SECTION_TITLES.x} busy={busy} onEdit={() => setEditing('x')}>
          <p className="creator-profile-bio">{profile.xProfile?.biography || '尚未收录X简介。'}</p>
          {profile.xProfile?.url && <CreatorPublicLink url={profile.xProfile.url} label="前往 X" />}
        </ProfileGroup>
        <ProfileGroup title={CREATOR_SECTION_TITLES.basic} busy={busy} onEdit={() => setEditing('basic')}>
          <p className="creator-group-label">公开昵称</p>
          <p>{profile.nickname || '尚未填写'}</p>
          <p className="creator-group-label">整理简介</p>
          <p className="creator-profile-bio creator-bio-preview">{profile.biography.trim().split(/\n\s*\n/, 1)[0] || '尚未填写'}</p>
          {profile.biography && <details className="creator-records">
            <summary>查看完整说明与核验记录</summary>
            <p className="creator-profile-bio">{profile.biography}</p>
          </details>}
        </ProfileGroup>
        <ProfileGroup title={CREATOR_SECTION_TITLES.sources} busy={busy} onEdit={() => setEditing('sources')}>
          <nav className="creator-platforms" aria-label="作者公开平台">
            {[...new Set([profile.homepageUrl, ...profile.evidenceUrls])].filter(Boolean).map(url => <CreatorPublicLink key={url} url={url} />)}
          </nav>
          <p className="creator-group-label">公开资料检查 · {profile.autoRefresh ? '已开启' : '已关闭'}</p>
          <button type="button" className="pill" disabled={busy} onClick={() => state.refresh.mutate()}>
            {state.refresh.isPending ? '检查中…' : '检查来源'}
          </button>
          {(profile.lastError || profile.lastSuccessAt) && <details className="creator-records">
            <summary>查看检查记录</summary>
            {profile.lastSuccessAt && <p>最近成功 {profile.lastSuccessAt}</p>}
            {profile.lastError && <p>{profile.lastError}</p>}
          </details>}
          {state.refresh.isError && <p role="alert">检查失败：{batchFailureReason(state.refresh.error)}</p>}
        </ProfileGroup>
        <ProfileGroup title={CREATOR_SECTION_TITLES.subscription} busy={busy} onEdit={() => setEditing('subscription')}>
          <p>{profile.feedUrl ? '已配置作品发布来源' : '尚未配置作品发布订阅'}</p>
          <p className="creator-group-label">订阅检查 · {profile.trackUpdates ? '已开启' : '已关闭'}</p>
          {profile.feedUrl && <CreatorPublicLink url={profile.feedUrl} label="查看订阅来源" />}
        </ProfileGroup>
      </div>}
      {profile && state.hasCandidate && <section className="creator-candidate" aria-label="待审核资料">
        <h3>待核对的候选资料</h3>
        <p>核对候选资料与来源后保存。</p>
        <p>{profile.candidate.nickname || '昵称沿用当前资料'}</p>
        <p className="creator-profile-bio">{profile.candidate.biography}</p>
        <nav className="creator-platforms">
          {[profile.candidate.homepageUrl, ...profile.candidate.evidenceUrls].filter(Boolean).map(url => <CreatorPublicLink key={url} url={url} />)}
        </nav>
        <button type="button" className="pill" disabled={busy} onClick={() => { state.applyCandidate(); setEditing('review') }}>
          填入候选草稿，继续核对
        </button>
      </section>}
    </>}
  </section>
}
