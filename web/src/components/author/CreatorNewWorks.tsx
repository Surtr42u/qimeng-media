import { Link } from 'react-router'
import { useCreatorProfile } from '@/hooks/use-creator-profile'
import { creatorXUrl } from '@/lib/creator-presentation'
import { authorProfilePath } from '@/lib/route-keys'
import { CreatorPublicLink } from './CreatorPublicLink'

/** 当前只消费作者显式配置的作品订阅，暂无订阅时提供发布入口。 */
export function CreatorNewWorks({ authorId }: { authorId: string }) {
  const state = useCreatorProfile(authorId)
  const profile = state.profile
  const xUrl = creatorXUrl(profile)
  const subscribed = !!profile?.feedUrl && profile.trackUpdates
  return <section className="rank-card creator-updates" aria-label="作者新作">
    <h3>作者新作</h3>
    {state.isPending ? <p role="status">正在读取发布来源…</p> : state.isError ? <p role="alert">发布来源读取失败，请重试资料。</p> : subscribed ? <>
      <p>来自已配置的作品发布订阅，点击标题查看原始发布页。</p>
      {profile.lastError && <p role="status">来源检查未完成，保留已保存记录。</p>}
      {profile.updates.length ? <ul>{profile.updates.map(update => <li key={update.id}><CreatorPublicLink url={update.url} label={update.title} /><span>{update.publishedAt}</span></li>)}</ul>
        : <p>{profile.lastCheckedAt ? '暂无已获取的作品发布记录。' : '作品订阅尚未检查。'}</p>}
    </> : <p>尚未接入可用的作品发布订阅，可前往作者发布页查看新作。</p>}
    <div className="creator-profile-actions">
      {xUrl && <CreatorPublicLink url={xUrl} label="查看 X 发布" />}
      <Link className="pill" to={authorProfilePath(authorId)}>发布来源与详细资料</Link>
    </div>
  </section>
}
