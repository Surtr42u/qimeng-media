import { useNavigate, useSearchParams } from 'react-router'
import { useState } from 'react'
import { MediaCard } from '@/components/media/MediaCard'
import { ContentRankGrid } from '@/components/data/ContentRankGrid'
import { assetToCard, useRecommendations } from '@/hooks/use-assets'
import { useRankings } from '@/hooks/use-stats'
import { parseRankPeriod, type HomeTabKey } from '@/lib/home-tabs'

/**
 * 首页：顶栏分类 tab（推荐/cos/排行榜）对应的内容区（tab 由 URL ?tab= 驱动，
 * TopBar 只写、本页只读——刷新/直达不丢态）。
 *   - recommend（缺省）：推荐卡片流（GET /recommendations，M3 十维推荐算法，
 *     常规流不含 COS——DOMAIN_RULES §6 隔离）；
 *   - cos：COS 推荐模式（GET /recommendations?cosOnly=true——旧版首页 COS
 *     tab 语义，同一套十维打分/每日惩罚跑在 COS 子集上，seed 换一批）；
 *   - hot：内容榜（GET /rankings，?period= 周期由顶栏周期行写入，缺省日榜；
 *     纯热度口径 view+play+like，DOMAIN_RULES §2）。
 * 卡片点击进详情（图片大图/视频播放）。
 */
export default function HomePage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const tab: HomeTabKey = (searchParams.get('tab') as HomeTabKey) ?? 'recommend'
  const period = parseRankPeriod(searchParams.get('period'))
  const hotRank = useRankings(period, 60)

  const openDetail = (id?: string): void => {
    if (id) navigate(`/app/asset/${id}`)
  }

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
            : <CosRecommendGrid onOpen={openDetail} />}
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
          {...assetToCard(a)}
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

/** COS 推荐卡片区（cos tab）：旧版「COS 推荐模式」——同套算法跑 COS 子集；
 *  seed>0 重新打散拉取（换一批），与服务端每日展示惩罚自然衔接。 */
function CosRecommendGrid({ onOpen }: { onOpen: (id?: string) => void }) {
  const [seed, setSeed] = useState(0)
  const { data: items = [], isLoading } = useRecommendations(60, seed, true)
  return (
    <>
      {items.map((a) => (
        <MediaCard
          key={a.id}
          {...assetToCard(a)}
          onClick={() => onOpen(a.id)}
        />
      ))}
      {isLoading && <p className="grid-empty">加载中…</p>}
      {!isLoading && items.length > 0 && (
        <p className="grid-empty">
          <button
            className="pill"
            type="button"
            onClick={() => setSeed(Date.now())}
          >
            换一批
          </button>
          <span className="pill-count">共 {items.length} 项</span>
        </p>
      )}
      {!isLoading && items.length === 0 && (
        <p className="grid-empty">暂无 COS 内容——先到「维护 → 文件管理」注册 COS 媒体库并扫描。</p>
      )}
    </>
  )
}
