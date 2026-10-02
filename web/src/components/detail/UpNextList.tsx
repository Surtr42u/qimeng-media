import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { Shuffle } from 'lucide-react'
import type { AssetSummary } from '@/api/generated'
import { useUpNextList, type MediaType } from '@/hooks/use-assets'
import { useThumbSize } from '@/hooks/use-thumb-size'
import { applyThumbSize } from '@/lib/thumb-size'
import { formatDuration } from '@/lib/format'
import { assetDetailWithSearch, readBackdropKey, type OverlayDetailState } from '@/lib/route-keys'

/**
 * 详情页「接下来播放」推荐栏（B站式右栏下部）：同类型推荐流（推荐算法 +
 * mediaType 收窄；协议无相似推荐参数，用户拍板口径），排除当前资产，
 * 换一批 = 换 seed 重取（同 seed 可复现，DOMAIN_RULES §1.1 禁纯随机）。
 * 行式列表 = 小缩略图（16:9 + 视频时长角标）+ 两行标题 + 作者副行，
 * 字段映射与列表卡 assetToCard 同口径（标题 cosWork 优先）。
 */
export function UpNextList({
  assetId,
  mediaType,
  cosWork,
}: {
  assetId: string
  mediaType?: MediaType
  cosWork?: string | null
}) {
  const [seed, setSeed] = useState(0)
  // P1（E1 返工）：行跳转原样携带当前查询串（?tab/?period）——叠加组导航
  // 统一走 assetDetailWithSearch，否则详情→详情丢查询串，底衬 HomePage 翻回
  // recommend、CosRecommendTab 卸载（换一批 seed 丢失），返回时流已重置
  const { search, state: locationState } = useLocation()
  // F5 底衬透传：行的详情→详情换件要延续当前详情的底衬归属（相册进的详情
  // 换件后返回仍回相册，否则布局按缺省 home 重挂底衬）。null = home 链入口
  // 未携带该字段 → 不写，state 与 E1 现状逐字节一致（零回归）
  const backdrop = readBackdropKey(locationState)
  const { data, isFetching } = useUpNextList({
    mediaType,
    // COS 资产的推荐流同区收窄（与首页 cos tab 同参数语义）
    cosOnly: !!cosWork,
    seed,
  })
  // 类型谓词收窄 id：协议 AssetSummary.id 可空，但本列表行必须有 id 才可跳详情
  const items = (data ?? []).filter((a): a is AssetSummary & { id: string } => !!a.id && a.id !== assetId)
  // E5 批次导航快照：本列表（排除当前资产后）的 id 序即流快照，行下标即所点
  // 位置，经 location.state 交详情页渲染上一件/下一件；AssetSummary 无 origUrl
  // 字段故不传 origUrls（邻项不预载，切换走详情接口 origUrl——拍板允许）
  const navIds = items.map((a) => a.id)
  // 档位偏好接入选点（与 MediaCard 同口径；auto=服务端下发原样）
  const [thumbSize] = useThumbSize()

  return (
    <div className="rank-card upnext-card">
      <div className="upnext-head">
        <h3>接下来播放</h3>
        <button className="shuffle-btn" onClick={() => setSeed(Date.now())} disabled={isFetching}>
          <Shuffle size={13} className={isFetching ? 'spin' : undefined} />
          换一批
        </button>
      </div>
      <div className="upnext-list">
        {items.map((a, i) => (
          <Link
            className="upnext-row"
            key={a.id}
            to={assetDetailWithSearch(a.id, search)}
            state={{ ids: navIds, index: i, ...(backdrop ? { backdrop } : {}) } satisfies OverlayDetailState}
          >
            <span className="upnext-thumb">
              {a.thumbUrl ? <img src={applyThumbSize(a.thumbUrl, thumbSize)} alt={a.fileName ?? ''} loading="lazy" /> : null}
              {a.mediaType === 'video' && a.durationMs ? (
                <i className="upnext-dur">{formatDuration(a.durationMs)}</i>
              ) : null}
            </span>
            <span className="upnext-info">
              <span className="upnext-title">{a.cosWork ?? a.fileName}</span>
              <span className="upnext-up">{a.authorNames?.[0] ?? a.source ?? ''}</span>
            </span>
          </Link>
        ))}
        {items.length === 0 ? <p className="grid-empty">暂无推荐</p> : null}
      </div>
    </div>
  )
}
