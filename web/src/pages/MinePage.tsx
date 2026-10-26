import { memo, useCallback } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { BarChart3, Database, Heart, History, Settings, Users, X } from 'lucide-react'
import { toast } from 'sonner'
import type { HistoryItem } from '@/api/generated'
import { MediaCard } from '@/components/media/MediaCard'
import { InfiniteTail } from '@/components/media/InfiniteTail'
import { MineContentTabs } from '@/components/shell/MineContentTabs'
import { SearchIcon } from '@/components/shell/icons'
import { LoadingHint } from '@/components/ui/loading-hint'
import { PageHeading } from '@/components/ui/page-heading'
import { QueryNotice } from '@/components/ui/query-notice'
import { SkeletonGrid } from '@/components/ui/skeleton-grid'
import { assetToCard } from '@/hooks/use-assets'
import { useMineWorkspace, type MineTab } from '@/hooks/use-mine-workspace'
import { useThumbSize } from '@/hooks/use-thumb-size'
import { LOCALE_ZH } from '@/lib/constants'
import { authorDisplayName, formatBytes, formatDateTime, formatDuration } from '@/lib/format'
import { batchFailureReason } from '@/lib/batch'
import { statsReadMessage } from '@/lib/stats-contract'
import { assetDetail, AUTHORS_PATH, COLLECTION_AUTHOR, DATA_PATH, HOME_PATH, MAINTENANCE_LIBRARIES_PATH, SETTINGS_PATH, collectionPath } from '@/lib/route-keys'
import { applyThumbSize } from '@/lib/thumb-size'

/** 历史卡渲染项：协议 HistoryItem + 客户端推导的「已看完」徽标 */
type HistRenderItem = HistoryItem & { isDone?: boolean }

/** 历史卡数据（结构照原型 HistCard：封面/标题/已看完徽标/观看时间/视频时长/出处）。
 *  onOpen 稳定回调模式（MediaCard 2026-09-20 审查同款口径）：本组件已 memo 化
 *  只认浅等 props，调用方逐条新建 onClick 闭包会让 memo 形同虚设——id 绑定
 *  移进卡片内部，页面级 onOpen 用 useCallback 稳定后全列表共用。 */
function HistCardItemImpl({ item, onOpen }: { item: HistRenderItem; onOpen?: (id?: string) => void }) {
  // 档位偏好接入选点（与 MediaCard 同口径；auto=服务端下发原样）
  const [thumbSize] = useThumbSize()
  const handleClick = onOpen ? () => onOpen(item.id) : undefined
  return (
    <article className="hist-card" onClick={handleClick} role={handleClick ? 'button' : undefined} tabIndex={handleClick ? 0 : undefined} aria-label={item.fileName} onKeyDown={event => { if (handleClick && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); handleClick() } }}>
      <div className="hc-cover">
        {/* 与 MediaCard 同款优化口径：lazy + 异步解码避免滚动掉帧 */}
        <img src={applyThumbSize(item.thumbUrl ?? '', thumbSize)} alt="" loading="lazy" decoding="async" />
        {item.isDone ? <span className="hc-done">已看完</span> : null}
        <span className="hc-time">{formatDateTime(item.lastViewedAt)}</span>
        {item.durationMs ? <span className="hc-duration">{formatDuration(item.durationMs)}</span> : null}
      </div>
      <p className="hc-title">{item.fileName ?? ''}</p>
      <p className="hc-up">{item.source ? `@ ${item.source}` : ''}</p>
    </article>
  )
}

/** memo 隔离（MediaCard 同款，2026-09-20 审查模式）：历史长列表中兄弟 state
 *  变化（tab 切换/搜索框输入）按 props 浅等跳过未变卡片。生效前提 = item
 *  引用稳定（isDone 盖章在 useMemo 派生层）+ onOpen 页面级 useCallback。 */
const HistCardItem = memo(HistCardItemImpl)

/** 个人内容分区独立反馈；标签及历史筛选写入URL，切回时不丢当前位置。 */
export default function MinePage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const tab: MineTab = params.get('tab') === 'fav' ? 'fav' : params.get('tab') === 'history' ? 'history' : 'follow'
  const query = params.get('q') ?? ''
  const changeParam = (key: string, value: string) => setParams(previous => { const next = new URLSearchParams(previous); if (value) next.set(key, value); else next.delete(key); return next }, { replace: true })
  const data = useMineWorkspace(tab, query)
  const { librariesQuery, overviewQuery, authorsQuery, toggleFollow, favQuery, histQuery, favItems, histItems, followedAuthors, histRenderGroups } = data
  const overview = overviewQuery.data
  const openCard = useCallback((id?: string) => { if (id) navigate(assetDetail(id)) }, [navigate])
  const retryFav = () => { if (favQuery.isFetchNextPageError) void favQuery.fetchNextPage({ cancelRefetch: false }); else void favQuery.refetch() }
  const retryHist = () => { if (histQuery.isFetchNextPageError) void histQuery.fetchNextPage({ cancelRefetch: false }); else void histQuery.refetch() }

  return <div className="page personal-workspace mine-workspace" id="page-mine">
    <PageHeading title="我的" description="关注的作者、收藏的作品与最近的浏览足迹" actions={<><Link className="workspace-link" to={DATA_PATH}><BarChart3 aria-hidden="true" />查看数据</Link><Link className="workspace-link" to={SETTINGS_PATH}><Settings aria-hidden="true" />设置</Link></>} />
    <section className="profile-card mine-hero">
      <div className="mine-hero-main"><span className="mine-hero-icon"><Database aria-hidden="true" /></span><div><h3>我的媒体空间</h3><p>当前服务器的媒体库与内容</p><Link to={MAINTENANCE_LIBRARIES_PATH}>管理媒体库</Link></div></div>
      <dl className="profile-stats"><div><dd>{librariesQuery.data == null ? '—' : librariesQuery.data.length.toLocaleString(LOCALE_ZH)}</dd><dt>已登记媒体库</dt></div><div><dd>{overview?.totalFiles == null ? '—' : overview.totalFiles.toLocaleString(LOCALE_ZH)}</dd><dt>文件总数</dt></div><div><dd>{overview?.totalSizeBytes == null ? '—' : formatBytes(overview.totalSizeBytes)}</dd><dt>总大小</dt></div></dl>
    </section>
    {librariesQuery.isError && <QueryNotice message="媒体库列表读取失败" retained={librariesQuery.data != null} busy={librariesQuery.isFetching} onRetry={() => { void librariesQuery.refetch() }} />}
    {overviewQuery.isError && <QueryNotice message={statsReadMessage(overviewQuery.error, '媒体库概览')} retained={!!overview} busy={overviewQuery.isFetching} onRetry={() => { void overviewQuery.refetch() }} />}
    <MineContentTabs tab={tab} onChange={next => changeParam('tab', next)} />
    <section className="m-pane mine-pane" role="tabpanel" id="mine-panel-follow" aria-labelledby="mine-tab-follow" hidden={tab !== 'follow'} tabIndex={0}>
      <div className="workspace-card-head"><div><h3><Users aria-hidden="true" />关注作者</h3><p className="rank-note">{authorsQuery.data ? `已关注 ${followedAuthors.length} 位作者 · 点名字查看作品` : '集中查看你关注的创作者'}</p></div><Link to={AUTHORS_PATH}>管理作者</Link></div>
      {authorsQuery.isError && <QueryNotice message="关注作者读取失败" retained={authorsQuery.data != null} busy={authorsQuery.isFetching} onRetry={() => { void authorsQuery.refetch() }} />}
      {authorsQuery.isPending ? <LoadingHint /> : followedAuthors.length ? <div className="follow-list mine-follow-grid">{followedAuthors.map(author => {
        const name = authorDisplayName(author)
        return <article className="follow-card" key={author.id}><Link className="mine-follow-content" to={collectionPath(COLLECTION_AUTHOR, name)}><span className="mine-author-avatar" aria-hidden="true">{name.slice(0, 1)}</span><div className="follow-info"><p className="follow-name">{name}</p><p className="follow-sub">{(author.fileCount ?? 0).toLocaleString(LOCALE_ZH)} 个文件</p></div></Link><button className="follow-btn" type="button" disabled={!author.id || toggleFollow.isPending || authorsQuery.isError} aria-label={`取消关注 ${name}`} onClick={() => { if (author.id) toggleFollow.mutate({ authorId: author.id, follow: false }, { onError: error => toast.error(`取消关注失败：${batchFailureReason(error)}`) }) }}>{toggleFollow.isPending && toggleFollow.variables?.authorId === author.id ? '处理中…' : '已关注'}</button></article>
      })}</div> : !authorsQuery.isError && <div className="workspace-empty"><Users aria-hidden="true" /><h4>暂无关注作者</h4><p>关注后，作者和作品入口会集中显示在这里。</p><Link to={AUTHORS_PATH}>去管理作者</Link></div>}
    </section>
    <section className="m-pane mine-pane" role="tabpanel" id="mine-panel-fav" aria-labelledby="mine-tab-fav" hidden={tab !== 'fav'} tabIndex={0}>
      <div className="workspace-card-head"><div><h3><Heart aria-hidden="true" />收藏作品</h3><p className="rank-note">按收藏时间从新到旧 · {favQuery.data ? `已加载 ${favItems.length} 项${favQuery.data.pages[0]?.totalMatched != null ? ` / 共 ${favQuery.data.pages[0].totalMatched} 项` : ''}` : '选择喜欢的作品，方便下次找到'}</p></div></div>
      {favQuery.isError && <QueryNotice message="收藏读取失败，不能据此判断没有收藏" retained={favItems.length > 0} busy={favQuery.isFetching} onRetry={retryFav} />}
      {favQuery.isLoading && favItems.length === 0 ? <SkeletonGrid className="media-grid" count={8} /> : favItems.length ? <div className="media-grid" id="favGrid">{favItems.map(item => <MediaCard key={item.id} {...assetToCard(item)} onOpen={openCard} />)}</div> : !favQuery.isError && <div className="workspace-empty"><Heart aria-hidden="true" /><h4>暂无收藏内容</h4><p>浏览作品时收藏，喜欢的内容就会留在这里。</p><Link to={HOME_PATH}>去浏览作品</Link></div>}
      <InfiniteTail isFetchingNextPage={favQuery.isFetchingNextPage} hasNextPage={favQuery.hasNextPage} itemCount={favItems.length} sentinelRef={data.favSentinelRef} />
      {favQuery.hasNextPage && <div className="workspace-load-more"><button className="pill" type="button" disabled={favQuery.isFetching} onClick={() => { void favQuery.fetchNextPage({ cancelRefetch: false }) }}>{favQuery.isFetchingNextPage ? '加载中…' : '加载更多收藏'}</button></div>}
    </section>
    <section className="m-pane mine-pane" role="tabpanel" id="mine-panel-history" aria-labelledby="mine-tab-history" hidden={tab !== 'history'} tabIndex={0}>
      <div className="workspace-card-head"><div><h3><History aria-hidden="true" />浏览历史</h3><p className="rank-note">每个文件最近一次浏览 · 已加载 {histItems.length} 项</p></div><div className="hist-search mine-history-search"><input type="search" aria-label="筛选已加载历史" placeholder="筛选已加载记录的文件名" value={query} onChange={event => changeParam('q', event.target.value)} /><SearchIcon /></div></div>
      {query.trim() && <div className="mine-history-filter"><p>仅筛选已加载的记录，可继续加载查找更多。</p><button className="workspace-link" type="button" onClick={() => changeParam('q', '')}><X aria-hidden="true" />清空筛选</button></div>}
      {histQuery.isError && <QueryNotice message="浏览历史读取失败，不能据此判断没有记录" retained={histItems.length > 0} busy={histQuery.isFetching} onRetry={retryHist} />}
      {histRenderGroups.map(group => <div className="hist-group2" key={group.key}><h3>{group.label}<span>{group.items.length} 项</span></h3><div className="hist-grid">{group.items.map(item => <HistCardItem key={item.id} item={item} onOpen={openCard} />)}</div></div>)}
      {histQuery.isLoading && histItems.length === 0 ? <LoadingHint /> : histRenderGroups.length === 0 && !histQuery.isError && <div className="workspace-empty"><History aria-hidden="true" /><h4>{query.trim() ? '已加载记录中没有匹配项' : '暂无浏览记录'}</h4><p>{query.trim() ? '换个文件名关键词，或继续加载更多历史。' : '打开作品后，最近浏览的内容会出现在这里。'}</p>{!query.trim() && <Link to={HOME_PATH}>去浏览作品</Link>}</div>}
      <InfiniteTail isFetchingNextPage={histQuery.isFetchingNextPage} hasNextPage={histQuery.hasNextPage} itemCount={histItems.length} sentinelRef={data.histSentinelRef} />
      {histQuery.hasNextPage && <div className="workspace-load-more"><button className="pill" type="button" disabled={histQuery.isFetching} onClick={() => { void histQuery.fetchNextPage({ cancelRefetch: false }) }}>{histQuery.isFetchingNextPage ? '加载中…' : '加载更多历史'}</button></div>}
    </section>
  </div>
}
