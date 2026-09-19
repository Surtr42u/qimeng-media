import { useCallback, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import type { HistoryItem } from '@/api/generated'
import { MediaCard } from '@/components/media/MediaCard'
import { InfiniteTail } from '@/components/media/InfiniteTail'
import { SearchIcon } from '@/components/shell/icons'
import { useAutoMore } from '@/hooks/use-auto-more'
import { assetToCard, useAssetsInfinite } from '@/hooks/use-assets'
import { useAuthors, useToggleFollow } from '@/hooks/use-authors'
import { useHistoryInfinite } from '@/hooks/use-history'
import { useLibraries } from '@/hooks/use-libraries'
import { useStatsOverview } from '@/hooks/use-stats'
import { DEFAULT_PAGE_SIZE, LOCALE_ZH } from '@/lib/constants'
import { formatBytes, formatDateTime, formatDuration } from '@/lib/format'
import { groupHistory } from '@/lib/history-grouping'
import { assetDetail } from '@/lib/route-keys'

/**
 * 我的页（原型 #page-mine 移植）：资料卡 + 三 Tab（关注/收藏/历史），阶段 B 已接真实数据。
 * 关注列表 = GET /authors 过滤 followed（用户拍板：只显示已关注，全部作者在作者管理页）；
 * 收藏 = GET /assets favorite 筛选（favoriteAt 倒序）；历史 = GET /history 按 lastViewedAt
 * 分「今天/昨天/更早」三组（已看完 = lastPositionSeconds >= durationMs/1000，GUIDE_API 口径）。
 */

type MineTab = 'follow' | 'fav' | 'history'

const TABS: { key: MineTab; label: string }[] = [
  { key: 'follow', label: '关注作者' },
  { key: 'fav', label: '收藏作品' },
  { key: 'history', label: '浏览历史' },
]

/** 历史卡渲染项：协议 HistoryItem + 客户端推导的「已看完」徽标 */
type HistRenderItem = HistoryItem & { isDone?: boolean }

/** 历史卡数据（结构照原型 HistCard：封面/标题/已看完徽标/观看时间/视频时长/出处） */
function HistCardItem({ item, onClick }: { item: HistRenderItem; onClick?: () => void }) {
  return (
    <article className="hist-card" onClick={onClick} role={onClick ? 'button' : undefined}>
      <div className="hc-cover">
        {/* 与 MediaCard 同款优化口径：lazy + 异步解码避免滚动掉帧 */}
        <img src={item.thumbUrl ?? ''} alt="" loading="lazy" decoding="async" />
        {item.isDone ? <span className="hc-done">已看完</span> : null}
        <span className="hc-time">{formatDateTime(item.lastViewedAt)}</span>
        {item.durationMs ? <span className="hc-duration">{formatDuration(item.durationMs)}</span> : null}
      </div>
      <p className="hc-title">{item.fileName ?? ''}</p>
      <p className="hc-up">{item.source ? `@ ${item.source}` : ''}</p>
    </article>
  )
}

export default function MinePage() {
  const navigate = useNavigate()
  const [tab, setTab] = useState<MineTab>('follow')
  const [query, setQuery] = useState('')

  // 稳定打开回调（MediaCard memo 生效前提，2026-09-20 全库审查）
  const openCard = useCallback(
    (id?: string) => {
      if (id) navigate(assetDetail(id))
    },
    [navigate],
  )

  const { data: libraries } = useLibraries()
  const { data: overview } = useStatsOverview()
  const { data: authors = [], isLoading: authorsLoading } = useAuthors()
  const toggleFollow = useToggleFollow()

  // 收藏：favoriteAt 倒序（协议 sort=favoriteAt 仅在收藏筛选下语义成立，DOMAIN_RULES §3）。
  // enabled 按 tab 挂起（2026-09-20 审查：此前进页即三连发——默认关注 tab 下
  // 收藏 60 条+历史 60 条白打；切换时再拉，缓存命中即时显示）
  const favQuery = useAssetsInfinite({ favorite: true, sort: 'favoriteAt', order: 'desc' }, tab === 'fav')
  const favItems = useMemo(() => favQuery.data?.pages.flatMap((p) => p.items ?? []) ?? [], [favQuery.data])

  const histQuery = useHistoryInfinite(DEFAULT_PAGE_SIZE, tab === 'history')
  const histItems = useMemo(
    () => histQuery.data?.pages.flatMap((p) => p.items ?? []) ?? [],
    [histQuery.data],
  )

  // E3 无感加载哨兵 ×2（收藏/历史两 pane 各自，对齐首页 use-auto-more 语义：
  // 触底提前 6 项拉下一页）。onHit 双守卫：isFetchingNextPage 防重复拉页；
  // isPlaceholderData 前瞻防混拼（useAssetsInfinite 已配 keepPreviousData，
  // 守卫在换键期间真实生效；useHistoryInfinite 无换键场景恒 false）。
  // pane 切换走 hidden 属性——display:none 下 IO 恒不相交，隐藏 pane 不会误拉。
  const favSentinelRef = useAutoMore(favQuery.hasNextPage, () => {
    if (!favQuery.isFetchingNextPage && !favQuery.isPlaceholderData) void favQuery.fetchNextPage()
  })
  const histSentinelRef = useAutoMore(histQuery.hasNextPage, () => {
    if (!histQuery.isFetchingNextPage && !histQuery.isPlaceholderData) void histQuery.fetchNextPage()
  })

  // 仅显示已关注（用户拍板语义：未关注作者在作者管理页处理）
  const followedAuthors = useMemo(() => authors.filter((a) => a.followed), [authors])

  // 分组：口径单源 lib/history-grouping.ts（今天/昨天/更早 + 标题子串过滤，ADR-0008 抽离）
  const histGroups = useMemo(() => groupHistory(histItems, query), [histItems, query])

  return (
    <div className="page" id="page-mine">
      <section className="profile-card">
        <div className="profile-main">
          <h2>绮梦</h2>
          <p>本地管理员</p>
        </div>
        <dl className="profile-stats">
          <div>
            <dd>{libraries?.length ?? 0}</dd>
            <dt>媒体库</dt>
          </div>
          <div>
            <dd>{(overview?.totalFiles ?? 0).toLocaleString(LOCALE_ZH)}</dd>
            <dt>文件总数</dt>
          </div>
          <div>
            <dd>{formatBytes(overview?.totalSizeBytes ?? 0)}</dd>
            <dt>总大小</dt>
          </div>
        </dl>
      </section>
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.key}
            className={tab === t.key ? 'tab-btn active' : 'tab-btn'}
            data-mtab={t.key}
            type="button"
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>
      {/* pane 显隐沿用原型 hidden 属性（CSS .m-pane[hidden] 已兜底 display:none） */}
      <div className="m-pane" id="mpane-follow" hidden={tab !== 'follow'}>
        {authorsLoading ? (
          <p className="a-empty">加载中…</p>
        ) : followedAuthors.length ? (
          <div className="follow-list">
            {followedAuthors.map((a) => (
              <article className="follow-card" key={a.id}>
                <div className="follow-info">
                  <p className="follow-name">{a.displayName}</p>
                  <p className="follow-sub">{a.fileCount ?? 0} 个文件</p>
                </div>
                <button
                  className={`follow-btn${a.followed ? '' : ' follow-btn--idle'}`}
                  type="button"
                  disabled={toggleFollow.isPending}
                  onClick={() => a.id && toggleFollow.mutate({ authorId: a.id, follow: !a.followed })}
                >
                  {a.followed ? '已关注' : '关注'}
                </button>
              </article>
            ))}
          </div>
        ) : (
          <p className="a-empty">暂无关注作者</p>
        )}
      </div>
      <div className="m-pane" id="mpane-fav" hidden={tab !== 'fav'}>
        <div className="hist-toolbar">
          <p className="m-note">收藏 · {favQuery.data?.pages[0]?.totalMatched ?? 0}</p>
        </div>
        <div className="media-grid" id="favGrid">
          {favItems.map((f) => (
            <MediaCard key={f.id} {...assetToCard(f)} onOpen={openCard} />
          ))}
          {favItems.length === 0 && !favQuery.isFetching ? (
            <p className="grid-empty">暂无收藏内容</p>
          ) : null}
        </div>
        {/* E3 无感加载尾部（三件套见 components/media/InfiniteTail） */}
        <InfiniteTail
          isFetchingNextPage={favQuery.isFetchingNextPage}
          hasNextPage={favQuery.hasNextPage}
          itemCount={favItems.length}
          sentinelRef={favSentinelRef}
        />
      </div>
      <div className="m-pane" id="mpane-history" hidden={tab !== 'history'}>
        <div className="hist-toolbar">
          <p className="m-note">按观看时间分组</p>
          <div className="hist-search">
            <input
              type="text"
              id="histSearch"
              placeholder="搜索你的历史记录"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <SearchIcon />
          </div>
        </div>
        {histGroups.map((g) => (
          <div className="hist-group2" key={g.key}>
            <h3>
              {g.label} · {g.items.length}
            </h3>
            <div className="hist-grid">
              {g.items.map((h) => (
                <HistCardItem
                  key={h.id}
                  item={{
                    ...h,
                    isDone: h.durationMs != null && h.lastPositionSeconds != null
                      && h.lastPositionSeconds * 1000 >= h.durationMs,
                  }}
                  onClick={() => h.id && navigate(assetDetail(h.id))}
                />
              ))}
            </div>
          </div>
        ))}
        {histGroups.length === 0 && !histQuery.isFetching ? (
          <p className="grid-empty">{query.trim() ? '没有匹配的历史记录' : '暂无浏览记录'}</p>
        ) : null}
        {/* E3 无感加载尾部（三件套见 components/media/InfiniteTail） */}
        <InfiniteTail
          isFetchingNextPage={histQuery.isFetchingNextPage}
          hasNextPage={histQuery.hasNextPage}
          itemCount={histItems.length}
          sentinelRef={histSentinelRef}
        />
      </div>
    </div>
  )
}
