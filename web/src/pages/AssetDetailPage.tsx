import { useEffect, useRef } from 'react'
import { useParams } from 'react-router'
import { useAssetDetail, useReportView } from '@/hooks/use-assets'
import { formatBytes, formatDuration, formatShortDate } from '@/lib/format'
import { ensureSessionId } from '@/hooks/use-session'

/**
 * 资产详情页（/app/asset/:assetId，首页/相册卡片点击进入）。
 * 图片/动图 = 签名原件直链大图（"查看永远发原件"）；视频 = 原生 <video> 直链播放
 * （播放器 UI 升级 ArtPlayer 为独立任务，协议/断点续播基座已就绪）。
 * 兼容性提示：ffprobe 编码（协议 0006）显示无法直链播放的编码提示，不转码。
 * 进入即上报 open 事件（DOMAIN_RULES §5 会话去重）。
 */

/** 浏览器 <video> 直链不支持的主流编码（其余编码尝试播放，失败再兜底） */
const INCOMPATIBLE_CODECS = /^(hevc|hvc1|hev1|av01|av1|vvc)$/i

export default function AssetDetailPage() {
  const { assetId } = useParams()
  const { data: d } = useAssetDetail(assetId)
  const reportView = useReportView()

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
        {isVideo ? (
          <video controls src={d.origUrl} poster={d.thumbUrl} />
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
