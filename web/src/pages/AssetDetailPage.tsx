import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { Star, ThumbsUp } from 'lucide-react'
import {
  useAssetDetail,
  useReportView,
  useSetFavorite,
  useToggleLike,
} from '@/hooks/use-assets'
import { useDwellReport } from '@/hooks/use-dwell-report'
import { useProgress, useTimelineTags } from '@/hooks/use-progress'
import VideoPlayer from '@/components/media/video-player'
import { AuthorCard } from '@/components/detail/AuthorCard'
import { AssetTagRow } from '@/components/detail/AssetTagRow'
import { UpNextList } from '@/components/detail/UpNextList'
import { formatBytes, formatCount, formatShortDate } from '@/lib/format'
import { ensureSessionId } from '@/hooks/use-session'

/**
 * 资产详情页（/app/asset/:assetId，首页/相册卡片点击进入）。
 * B站式双栏排版（2026-09-05 大改）：左主列 = 媒体舞台 → 标题 → 信息行 →
 * 点赞/收藏互动行 → 标签行；右栏 = 作者卡（关注）+「接下来播放」推荐栏。
 * 图片/动图 = 签名原件直链大图（"查看永远发原件"）；视频 = ArtPlayer 播放器
 * （W-3：倍速/静音/全屏/断点续播/时间轴标签打点回看，网络逻辑全在
 * hooks/use-progress.ts）。打点（DOMAIN_RULES §5）：进入上报 open、
 * 视频每次起播上报 play、停留时长上报 dwell（图片视频通用）。
 * 编码兼容提示条已按用户拍板移除（2026-09-05：用户浏览器可直放 hevc，
 * 提示条是常驻噪声；播放器播放失败时由 ArtPlayer 自身错误态兜底）。
 */

/** 已看完判定（协议明文口径，客户端推导）：lastPositionSeconds >= durationMs/1000 */
function isWatched(
  lastPositionSeconds: number | null | undefined,
  durationMs: number | null | undefined,
): boolean {
  return (
    lastPositionSeconds != null &&
    durationMs != null &&
    durationMs > 0 &&
    lastPositionSeconds >= durationMs / 1000
  )
}

export default function AssetDetailPage() {
  const { assetId } = useParams()
  const { data: d } = useAssetDetail(assetId)
  const { data: timelineTags, isLoading: tagsLoading } = useTimelineTags(assetId)
  const reportView = useReportView()
  // 进度上报：tick=播放中心跳（5s 节流）、flush=暂停/卸载立即上报（hooks/use-progress.ts）
  const progress = useProgress(assetId ?? '')
  // 互动行（B站式）：点赞 toggle（响应 LikeState 回填）/ 收藏显式设置（hooks/use-assets.ts）
  const toggleLike = useToggleLike()
  const setFavorite = useSetFavorite()

  // 已看完徽标与续播起点渲染期直接推导（协议明文口径，客户端推导）；
  // 起点的「定格防 refetch 重建」由 VideoPlayer 挂载时冻结 + key 绑定资产实现
  const watched = !!d && isWatched(d.lastPositionSeconds, d.durationMs)
  const startTime = watched ? 0 : (d?.lastPositionSeconds ?? 0)

  // open 打点：每资产只报一次（会话去重由服务端按 assetId+kind+sessionId+当日）。
  // ref 记「已上报资产」而非布尔：UpNextList 的详情→详情导航同路由只换参数、
  // 组件不重挂载（reviewer P1），布尔守卫会让新资产 open 永不上报
  const reportedFor = useRef<string | null>(null)
  useEffect(() => {
    if (!assetId || reportedFor.current === assetId) return
    reportedFor.current = assetId
    reportView.mutate({
      assetId,
      kind: 'open',
      startedAt: new Date().toISOString(),
      sessionId: ensureSessionId(),
    })
  }, [assetId, reportView])

  // play 打点：视频每次起播如实上报一条（同会话当日重复起播的去重由服务端
  // 判定，重复上报被其 202 幂等吸收——DOMAIN_RULES §5「同会话只计一次」）
  const reportPlay = useCallback(() => {
    if (!assetId) return
    reportView.mutate({
      assetId,
      kind: 'play',
      startedAt: new Date().toISOString(),
      sessionId: ensureSessionId(),
    })
  }, [assetId, reportView])

  // dwell 打点：进入计时、离开/页面隐藏 flush 恰好一条（累加口径的防重
  // 闸门在 hook 内部；图片与视频详情页通用）
  useDwellReport(assetId)

  // 点赞图标弹跳动效：每次点击重触发（类挂上→动画结束复位），onAnimationEnd 冒泡到按钮
  const [likeBounce, setLikeBounce] = useState(false)

  if (!d) {
    return (
      <div className="page" id="page-asset">
        <p className="grid-empty">加载中…</p>
      </div>
    )
  }

  const isVideo = d.mediaType === 'video'

  return (
    <div className="page" id="page-asset">
      <div className="detail-layout">
        <div className="detail-main">
          <div className="asset-stage">
            {watched ? <span className="watched-badge">已看完</span> : null}
            {isVideo ? (
              tagsLoading ? null : (
                <VideoPlayer
                  key={d.id ?? assetId}
                  src={d.origUrl ?? ''}
                  poster={d.thumbUrl}
                  startTime={startTime}
                  highlights={(timelineTags ?? [])
                    .filter((t) => t.timeMillis != null)
                    .map((t) => ({ time: (t.timeMillis ?? 0) / 1000, text: t.name ?? '' }))}
                  onTimeUpdate={progress.tick}
                  onPause={progress.flush}
                  onPlay={reportPlay}
                />
              )
            ) : d.origUrl ? (
              <img src={d.origUrl} alt={d.fileName} loading="eager" />
            ) : (
              <p className="grid-empty">无法加载内容</p>
            )}
          </div>
          <h1 className="detail-title">{d.cosWork ?? d.fileName}</h1>
          <p className="detail-meta">
            <span>浏览 {formatCount(d.viewCount)}</span>
            <span>播放 {formatCount(d.playCount)}</span>
            <span>{formatBytes(d.sizeBytes ?? 0)}</span>
            {d.width ? <span>{d.width}×{d.height}</span> : null}
            <span>{formatShortDate(d.modifiedAt)}</span>
            {d.source ? <span>{d.source}</span> : null}
          </p>
          <div className="detail-actions">
            <button
              className={`detail-act${(d.likedToday ?? false) ? ' active' : ''}${likeBounce ? ' bounce' : ''}`}
              onClick={() => {
                if (!assetId) return
                setLikeBounce(true)
                toggleLike.mutate(assetId)
              }}
              onAnimationEnd={() => setLikeBounce(false)}
              disabled={toggleLike.isPending || !assetId}
              title={(d.likedToday ?? false) ? '取消今日赞' : '点赞（每资产每日一次）'}
            >
              <ThumbsUp />
              <b>{formatCount(d.likeCount)}</b>
            </button>
            <button
              className={`detail-act${d.isFavorite ? ' active' : ''}`}
              onClick={() => {
                if (!assetId) return
                setFavorite.mutate({ assetId, favorite: !(d.isFavorite ?? false) })
              }}
              disabled={setFavorite.isPending || !assetId}
              title={d.isFavorite ? '取消收藏' : '收藏'}
            >
              <Star />
              <b>{d.isFavorite ? '已收藏' : '收藏'}</b>
            </button>
          </div>
          <AssetTagRow assetId={assetId!} tags={d.tags ?? []} />
        </div>
        <aside className="detail-side">
          <AuthorCard authors={d.authors ?? []} />
          <UpNextList assetId={assetId!} mediaType={d.mediaType} cosWork={d.cosWork} />
        </aside>
      </div>
    </div>
  )
}
