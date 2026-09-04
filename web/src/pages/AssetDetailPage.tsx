import { useCallback, useEffect, useRef } from 'react'
import { useParams } from 'react-router'
import { useAssetDetail, useReportView } from '@/hooks/use-assets'
import { useDwellReport } from '@/hooks/use-dwell-report'
import { useProgress, useTimelineTags } from '@/hooks/use-progress'
import VideoPlayer from '@/components/media/video-player'
import { formatBytes, formatDuration, formatShortDate } from '@/lib/format'
import { ensureSessionId } from '@/hooks/use-session'

/**
 * 资产详情页（/app/asset/:assetId，首页/相册卡片点击进入）。
 * 图片/动图 = 签名原件直链大图（"查看永远发原件"）；视频 = ArtPlayer 播放器
 * （W-3：倍速 0.5~3x/静音/全屏/断点续播/时间轴标签打点回看，网络逻辑全在
 * hooks/use-progress.ts）。兼容性提示：ffprobe 编码（协议 0006）显示无法
 * 直链播放的编码提示，不转码。打点（DOMAIN_RULES §5）：进入上报 open、
 * 视频每次起播上报 play、停留时长上报 dwell（图片视频通用）。
 */

/** 浏览器 <video> 直链不支持的主流编码（其余编码尝试播放，失败再兜底） */
const INCOMPATIBLE_CODECS = /^(hevc|hvc1|hev1|av01|av1|vvc)$/i

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

  // 已看完徽标与续播起点渲染期直接推导（协议明文口径，客户端推导）；
  // 起点的「定格防 refetch 重建」由 VideoPlayer 挂载时冻结 + key 绑定资产实现
  const watched = !!d && isWatched(d.lastPositionSeconds, d.durationMs)
  const startTime = watched ? 0 : (d?.lastPositionSeconds ?? 0)

  // open 打点：每详情实例只报一次（会话去重由服务端按 assetId+kind+sessionId+当日）
  const reported = useRef(false)
  useEffect(() => {
    if (!assetId || reported.current) return
    reported.current = true
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

  if (!d) {
    return (
      <div className="page" id="page-asset">
        <p className="grid-empty">加载中…</p>
      </div>
    )
  }

  const isVideo = d.mediaType === 'video'
  const codecWarn = isVideo && d.videoCodec && INCOMPATIBLE_CODECS.test(d.videoCodec)

  return (
    <div className="page" id="page-asset">
      {codecWarn ? (
        <div className="codec-warn">
          此视频编码为 {d.videoCodec}，当前浏览器可能无法直接播放（项目约定始终播放原件、不转码）。
        </div>
      ) : null}
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
      <div className="rank-card">
        <table className="log-table">
          <tbody>
            <tr><td style={{ width: 110, color: 'var(--text-sub)' }}>文件名</td><td>{d.fileName}</td></tr>
            <tr><td style={{ color: 'var(--text-sub)' }}>大小</td><td>{formatBytes(d.sizeBytes ?? 0)}</td></tr>
            <tr><td style={{ color: 'var(--text-sub)' }}>出处</td><td>{d.source ?? '未识别'}</td></tr>
            {isVideo ? (
              <tr><td style={{ color: 'var(--text-sub)' }}>时长</td><td>{d.durationMs ? formatDuration(d.durationMs) : '-'}</td></tr>
            ) : null}
            <tr><td style={{ color: 'var(--text-sub)' }}>浏览 / 播放</td><td>{d.viewCount ?? 0} / {d.playCount ?? 0}</td></tr>
            <tr><td style={{ color: 'var(--text-sub)' }}>修改时间</td><td>{formatShortDate(d.modifiedAt)}</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  )
}
