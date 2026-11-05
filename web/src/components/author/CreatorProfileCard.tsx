import { useState } from 'react'
import { Link } from 'react-router'
import type { Author } from '@/api/generated'
import { useCreatorAvatar, useCreatorProfile } from '@/hooks/use-creator-profile'
import { useToggleFollow } from '@/hooks/use-authors'
import { batchFailureReason } from '@/lib/batch'
import { creatorXUrl } from '@/lib/creator-presentation'
import { authorProfilePath } from '@/lib/route-keys'
import { CreatorPublicLink } from './CreatorPublicLink'

/** 主卡仅保留X原文与X入口，完整资料和维护进入独立子页。 */
export function CreatorProfileCard({ author }: { author: Author & { id: string } }) {
  const state = useCreatorProfile(author.id)
  const profile = state.profile
  const avatar = useCreatorAvatar(author.id, profile?.avatarUrl ?? '', profile?.revision ?? 0)
  const follow = useToggleFollow()
  const xUrl = creatorXUrl(profile)
  const [chinese, setChinese] = useState(true)
  const translation = profile?.xProfile?.biographyZh
  const showChinese = chinese && !!translation
  return <section className="rank-card creator-profile" aria-label="作者档案">
    <div className="creator-profile-identity">
      <div className="creator-profile-avatar">{avatar ? <img src={avatar} alt="" /> : Array.from(author.displayName ?? '?')[0]}</div>
      <div className="creator-profile-name"><h2>{author.displayName}</h2><p>{author.type === 'cos' ? 'COS 作者' : '创作者'} · {author.fileCount ?? '—'} 个本地文件</p></div>
      <button type="button" className="pill" disabled={follow.isPending} onClick={() => follow.mutate({ authorId: author.id, follow: !author.followed })}>{author.followed ? '已关注' : '关注'}</button>
    </div>
    {profile?.xProfile?.biography && <p className="creator-profile-bio" aria-label={showChinese ? 'X 简介中文' : 'X 简介原文'} lang={showChinese ? 'zh-CN' : undefined}>{showChinese ? translation : profile.xProfile.biography}</p>}
    <div className="creator-profile-actions">
      {xUrl && <CreatorPublicLink url={xUrl} label="前往 X" />}
      <Link className="pill" to={authorProfilePath(author.id)}>详细资料</Link>
      {translation && <div className="creator-language" role="group" aria-label="简介显示语言">
        <button type="button" aria-pressed={chinese} onClick={() => setChinese(true)}>中文</button>
        <button type="button" aria-pressed={!chinese} onClick={() => setChinese(false)}>原文</button>
      </div>}
    </div>
    {state.isPending && <p role="status">正在读取作者资料…</p>}
    {state.isError && <div role="alert"><p>作者资料读取失败：{batchFailureReason(state.error)}</p><button type="button" className="pill" onClick={() => { void state.refetch() }}>重试资料</button></div>}
    {follow.isError && <p role="alert">关注操作失败：{batchFailureReason(follow.error)}</p>}
  </section>
}
