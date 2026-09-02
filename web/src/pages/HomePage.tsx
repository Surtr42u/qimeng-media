import { useNavigate } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { useRecommendations } from '@/hooks/use-assets'
import { formatDuration, formatShortDate } from '@/lib/format'

/**
 * 首页：推荐卡片流（阶段 B 已接真实数据——GET /recommendations，M3 十维推荐算法）。
 * 顶栏分类 tab（推荐/cos/排行榜）由 TopBar 驱动；cos 独立入口暂同流（协议 includeCos
 * 参数接入待办）。卡片点击进详情（图片大图/视频播放）。
 */
export default function HomePage() {
  const navigate = useNavigate()
  const { data: items = [], isLoading } = useRecommendations(60)

  return (
    <div className="page" id="page-home">
      <div className="grid">
        {items.map((a) => (
          <MediaCard
            key={a.id}
            id={a.id}
            cover={a.thumbUrl ?? ''}
            title={a.fileName ?? ''}
            duration={a.durationMs ? formatDuration(a.durationMs) : undefined}
            up={a.source ?? undefined}
            date={formatShortDate(a.modifiedAt)}
            onClick={() => a.id && navigate(`/app/asset/${a.id}`)}
          />
        ))}
        {isLoading && <p className="grid-empty">加载中…</p>}
        {!isLoading && items.length === 0 && (
          <p className="grid-empty">还没有内容——先到「维护 → 文件管理」注册一个媒体库并扫描。</p>
        )}
      </div>
    </div>
  )
}
