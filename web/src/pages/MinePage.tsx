import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import type { HistoryItem } from '@/api/generated'
import { MediaCard } from '@/components/media/MediaCard'
import { SearchIcon } from '@/components/shell/icons'
import { assetToCard, useAssetsInfinite } from '@/hooks/use-assets'
import { useAuthors, useToggleFollow } from '@/hooks/use-authors'
import { useHistoryInfinite } from '@/hooks/use-history'
import { useLibraries } from '@/hooks/use-libraries'
import { useStatsOverview } from '@/hooks/use-stats'
import { LOCALE_ZH } from '@/lib/constants'
import { formatBytes, formatDateTime, formatDuration, localDateKey } from '@/lib/format'

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

/** 历史分组（顺序固定：今天 → 昨天 → 更早；空组隐藏） */
const HIST_GROUPS = [
  { key: 'today', label: '今天' },
  { key: 'yesterday', label: '昨天' },
  { key: 'earlier', label: '更早' },
] as const

type HistGroupKey = (typeof HIST_GROUPS)[number]['key']

/** 本地今天/昨天日期键（历史分组边界；跨零点后下次渲染自动更新） */
function dayBoundaryKeys(): { today: string; yesterday: string } {
  const now = new Date()
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  return { today: localDateKey(now.getTime()), yesterday: localDateKey(yesterday.getTime()) }
}

function histGroupOf(ts: number, today: string, yesterday: string): HistGroupKey {
  const k = localDateKey(ts)
  if (k === today) return 'today'
  if (k === yesterday) return 'yesterday'
  return 'earlier'
}

/** 历史卡渲染项：协议 HistoryItem + 客户端推导的「已看完」徽标 */
type HistRenderItem = HistoryItem & { isDone?: boolean }

/** 历史卡数据（结构照原型 HistCard：封面/标题/已看完徽标/观看时间/视频时长/出处） */
function HistCardItem({ item, onClick }: { item: HistRenderItem; onClick?: () => void }) {
  return (
    <article className="hist-card" onClick={onClick} role={onClick ? 'button' : undefined}>
      <div className="hc-cover">
        <img src={item.thumbUrl ?? ''} alt="" />
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

  const { data: libraries } = useLibraries()
  const { data: overview } = useStatsOverview()
  const { data: authors = [], isLoading: authorsLoading } = useAuthors()
  const toggleFollow = useToggleFollow()

  // 收藏：favoriteAt 倒序（协议 sort=favoriteAt 仅在收藏筛选下语义成立，DOMAIN_RULES §3）
  const favQuery = useAssetsInfinite({ favorite: true, sort: 'favoriteAt', order: 'desc' })
  const favItems = useMemo(() => favQuery.data?.pages.flatMap((p) => p.items ?? []) ?? [], [favQuery.data])

  const histQuery = useHistoryInfinite()
  const histItems = useMemo(
    () => histQuery.data?.pages.flatMap((p) => p.items ?? []) ?? [],
    [histQuery.data],
  )

  // 仅显示已关注（用户拍板语义：未关注作者在作者管理页处理）
  const followedAuthors = useMemo(() => authors.filter((a) => a.followed), [authors])

  // 分组：组内按 lastViewedAt 倒序；标题子串（fileName）过滤；空组隐藏
  const histGroups = useMemo(() => {
    const q = query.trim().toLowerCase()
    const { today, yesterday } = dayBoundaryKeys()
    const matched = histItems.filter(
      (h) =>
        (h.lastViewedAt ?? 0) > 0 &&
        (!q || (h.fileName ?? '').toLowerCase().includes(q)),
    )
    return HIST_GROUPS.map((g) => ({
      ...g,
      items: matched
        .filter((h) => histGroupOf(h.lastViewedAt as number, today, yesterday) === g.key)
        .sort((a, b) => (b.lastViewedAt ?? 0) - (a.lastViewedAt ?? 0)),
    })).filter((g) => g.items.length > 0)
  }, [histItems, query])

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
            <MediaCard
              key={f.id}
              {...assetToCard(f)}
              onClick={() => f.id && navigate(`/app/asset/${f.id}`)}
            />
          ))}
          {favItems.length === 0 && !favQuery.isFetching ? (
            <p className="grid-empty">暂无收藏内容</p>
          ) : null}
        </div>
        {favQuery.hasNextPage ? (
          <p className="grid-empty">
            <button
              className="pill"
              type="button"
              disabled={favQuery.isFetching}
              onClick={() => favQuery.fetchNextPage()}
            >
              {favQuery.isFetching ? '加载中…' : '加载更多'}
            </button>
            <span className="pill-count">共 {favItems.length} 项</span>
          </p>
        ) : null}
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
                  onClick={() => h.id && navigate(`/app/asset/${h.id}`)}
                />
              ))}
            </div>
          </div>
        ))}
        {histGroups.length === 0 && !histQuery.isFetching ? (
          <p className="grid-empty">{query.trim() ? '没有匹配的历史记录' : '暂无浏览记录'}</p>
        ) : null}
        {histQuery.hasNextPage ? (
          <p className="grid-empty">
            <button
              className="pill"
              type="button"
              disabled={histQuery.isFetching}
              onClick={() => histQuery.fetchNextPage()}
            >
              {histQuery.isFetching ? '加载中…' : '加载更多'}
            </button>
            <span className="pill-count">共 {histItems.length} 项</span>
          </p>
        ) : null}
      </div>
    </div>
  )
}
