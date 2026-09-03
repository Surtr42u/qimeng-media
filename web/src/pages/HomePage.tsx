import { useNavigate, useSearchParams } from 'react-router'
import { useMemo } from 'react'
import { MediaCard } from '@/components/media/MediaCard'
import { ContentRankGrid } from '@/components/data/ContentRankGrid'
import { useAssetsInfinite, useRecommendations } from '@/hooks/use-assets'
import { useRankings } from '@/hooks/use-stats'
import { formatDuration, formatShortDate } from '@/lib/format'
import { parseRankPeriod, type HomeTabKey } from '@/lib/home-tabs'

/**
 * 首页：顶栏分类 tab（推荐/cos/排行榜）对应的内容区（tab 由 URL ?tab= 驱动，
 * TopBar 只写、本页只读——刷新/直达不丢态）。
 *   - recommend（缺省）：推荐卡片流（GET /recommendations，M3 十维推荐算法，
 *     常规流不含 COS——DOMAIN_RULES §6 隔离）；
 *   - cos：COS 分区浏览流（GET /assets cosOnly=true；COS 资产独立入口）；
 *   - hot：内容榜（GET /rankings，?period= 周期由顶栏周期行写入，缺省日榜；
 *     纯热度口径 view+play+like，DOMAIN_RULES §2）。
 * 卡片点击进详情（图片大图/视频播放）。
 */
export default function HomePage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const tab: HomeTabKey = (searchParams.get('tab') as HomeTabKey) ?? 'recommend'

  // cos / hot 两个 tab 的流式数据（react-query 按 params 缓存；切换 tab 即换 key）
  const cosStream = useAssetsInfinite({ cosOnly: true, sort: 'default', order: 'desc', limit: 60 }, tab === 'cos')
  const cosItems = useMemo(() => cosStream.data?.pages.flatMap((p) => p.items ?? []) ?? [], [cosStream.data])
  const period = parseRankPeriod(searchParams.get('period'))
  const hotRank = useRankings(period, 60)

  const openDetail = (id?: string): void => {
    if (id) navigate(`/app/asset/${id}`)
  }
  const cosActive = tab === 'cos'
  const hotActive = tab === 'hot'
  const isLoading = cosActive ? cosStream.isFetching : hotActive ? hotRank.isLoading : false

  return (
    <div className="page" id="page-home">
      {tab === 'hot' ? (
        <div className="rank-card">
          <div className="rank-head">
            <h3>内容榜</h3>
            <span className="rank-note">按浏览量</span>
          </div>
          {hotRank.isLoading ? (
            <p className="grid-empty">加载中…</p>
          ) : (
            <ContentRankGrid
              items={hotRank.data ?? []}
              onOpen={(a) => openDetail(a.id)}
            />
          )}
        </div>
      ) : (
        <div className="grid">
          {tab === 'recommend'
            ? <RecommendGrid onOpen={openDetail} />
            : cosItems.map((a) => (
                <MediaCard
                  key={a.id}
                  id={a.id}
                  cover={a.thumbUrl ?? ''}
                  title={a.fileName ?? ''}
                  duration={a.durationMs ? formatDuration(a.durationMs) : undefined}
                  up={a.source ?? undefined}
                  date={formatShortDate(a.modifiedAt)}
                  onClick={() => openDetail(a.id)}
                />
              ))}
          {isLoading && cosActive && <p className="grid-empty">加载中…</p>}
          {!isLoading && cosActive && cosItems.length === 0 && (
            <p className="grid-empty">暂无 COS 内容——先到「维护 → 文件管理」注册 COS 媒体库并扫描。</p>
          )}
          {cosActive && cosStream.hasNextPage && (
            <p className="grid-empty">
              <button
                className="pill"
                type="button"
                disabled={cosStream.isFetching}
                onClick={() => cosStream.fetchNextPage()}
              >
                {cosStream.isFetching ? '加载中…' : '加载更多'}
              </button>
              <span className="pill-count">共 {cosItems.length} 项</span>
            </p>
          )}
        </div>
      )}
    </div>
  )
}

/** 推荐流卡片区（recommend tab；数据只取一次、无分页——首页推流口径） */
function RecommendGrid({ onOpen }: { onOpen: (id?: string) => void }) {
  const { data: items = [], isLoading } = useRecommendations(60)
  return (
    <>
      {items.map((a) => (
        <MediaCard
          key={a.id}
          id={a.id}
          cover={a.thumbUrl ?? ''}
          title={a.fileName ?? ''}
          duration={a.durationMs ? formatDuration(a.durationMs) : undefined}
          up={a.source ?? undefined}
          date={formatShortDate(a.modifiedAt)}
          onClick={() => onOpen(a.id)}
        />
      ))}
      {isLoading && <p className="grid-empty">加载中…</p>}
      {!isLoading && items.length === 0 && (
        <p className="grid-empty">还没有内容——先到「维护 → 文件管理」注册一个媒体库并扫描。</p>
      )}
    </>
  )
}
