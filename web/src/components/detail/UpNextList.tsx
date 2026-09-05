import { useState } from 'react'
import { Link } from 'react-router'
import { Shuffle } from 'lucide-react'
import { useUpNextList, type MediaType } from '@/hooks/use-assets'
import { formatDuration } from '@/lib/format'

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
  const { data, isFetching } = useUpNextList({
    mediaType,
    // COS 资产的推荐流同区收窄（与首页 cos tab 同参数语义）
    cosOnly: !!cosWork,
    seed,
  })
  const items = (data ?? []).filter((a) => a.id && a.id !== assetId)

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
        {items.map((a) => (
          <Link className="upnext-row" key={a.id} to={`/app/asset/${a.id}`}>
            <span className="upnext-thumb">
              {a.thumbUrl ? <img src={a.thumbUrl} alt={a.fileName ?? ''} loading="lazy" /> : null}
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
