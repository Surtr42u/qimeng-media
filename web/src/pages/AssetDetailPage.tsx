import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router'
import { ChevronLeft, ChevronRight, Star, ThumbsUp } from 'lucide-react'
import {
  useAssetDetail,
  useReportView,
  useSetFavorite,
  useToggleLike,
} from '@/hooks/use-assets'
import { useDwellReport } from '@/hooks/use-dwell-report'
import { useProgress, useTimelineTags } from '@/hooks/use-progress'
import VideoPlayer from '@/components/media/video-player'
import ImageViewer, { PRELOAD_AROUND } from '@/components/media/image-viewer'
import { AuthorCard } from '@/components/detail/AuthorCard'
import { AssetTagRow } from '@/components/detail/AssetTagRow'
import { FileOpsButton } from '@/components/detail/FileOpsButton'
import { UpNextList } from '@/components/detail/UpNextList'
import { formatBytes, formatCount, formatShortDate } from '@/lib/format'
import { assetDetailWithSearch, readAssetNavState, type AssetNavState } from '@/lib/route-keys'
import { ensureSessionId } from '@/hooks/use-session'

/**
 * 资产详情页（/app/asset/:assetId，首页/相册卡片点击进入）。
 * E1 起为叠加层形态：路由收进首页叠加组，本页以 .asset-overlay（fixed 覆盖
 * 内容区、自身滚动）打开，首页列表常驻底衬不卸载；滚动复位不再由 AppShell
 * 代管（.content 已对叠加组加门），详情→详情（右栏换资产）改由本页自回顶。
 * B站式双栏排版（2026-09-05 大改）：左主列 = 媒体舞台 → 标题 → 信息行 →
 * 点赞/收藏互动行 → 标签行；右栏 = 作者卡（关注）+「接下来播放」推荐栏。
 * 图片/动图 = 签名原件直链大图（"查看永远发原件"），E5 起点击打开自研
 * 图片查看器（缩放/横滑换件，components/media/image-viewer.tsx），并有列表
 * 上下文批次导航（上一件/下一件，数据源=入口经 location.state 传入的流快照）；
 * 视频 = ArtPlayer 播放器
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
  // isPlaceholderData（P2 占位闸门）：详情→详情换件、useAssetDetail 的
  // keepPreviousData 窗口期 d 仍是上一资产——此窗口内播放/停留上报全部停门
  const { data: d, isPlaceholderData } = useAssetDetail(assetId)
  const { data: timelineTags, isLoading: tagsLoading } = useTimelineTags(assetId)
  const reportView = useReportView()
  // 进度上报：tick=播放中心跳（5s 节流）、flush=暂停/卸载立即上报（hooks/use-progress.ts）；
  // 占位窗口停门（enabled=false），防上一资产的播放位置错记进 URL 新资产
  const reportingLive = !isPlaceholderData
  const progress = useProgress(assetId ?? '', reportingLive)
  // 互动行（B站式）：点赞 toggle（响应 LikeState 回填）/ 收藏显式设置（hooks/use-assets.ts）
  const toggleLike = useToggleLike()
  const setFavorite = useSetFavorite()

  // 已看完徽标与续播起点渲染期直接推导（协议明文口径，客户端推导）；
  // 起点的「定格防 refetch 重建」由 VideoPlayer 挂载时冻结 + key 绑定资产实现
  const watched = !!d && isWatched(d.lastPositionSeconds, d.durationMs)
  const startTime = watched ? 0 : (d?.lastPositionSeconds ?? 0)

  // open 打点：每资产只报一次（会话去重由服务端按 assetId+kind+sessionId+当日）。
  // ref 记「已上报资产」而非布尔：UpNextList 的详情→详情导航同路由只换参数、
  // 组件不重挂载（reviewer P1），布尔守卫会让新资产 open 永不上报。
  // open 不参与 P2 占位闸门：进详情是导航事实，与画面是否已切换无关
  // （服务端按 assetId+当日会话去重，不会重复计）
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
  // 判定，重复上报被其 202 幂等吸收——DOMAIN_RULES §5「同会话只计一次」）；
  // P2 占位窗口（画面仍属上一资产）不起播上报
  const reportPlay = useCallback(() => {
    if (!assetId || isPlaceholderData) return
    reportView.mutate({
      assetId,
      kind: 'play',
      startedAt: new Date().toISOString(),
      sessionId: ensureSessionId(),
    })
  }, [assetId, reportView, isPlaceholderData])

  // dwell 打点：进入计时、离开/页面隐藏 flush 恰好一条（累加口径的防重
  // 闸门在 hook 内部；图片与视频详情页通用）。P2 占位窗口传 undefined：
  // hook 对空 assetId 不开段（数据到货后随 assetId 变化正常开段），
  // 防看上一资产画面的停留时长错记进 URL 新资产
  useDwellReport(isPlaceholderData ? undefined : assetId)

  // 点赞图标弹跳动效：每次点击重触发（类挂上→动画结束复位），onAnimationEnd 冒泡到按钮
  const [likeBounce, setLikeBounce] = useState(false)

  // E5 批次导航上下文：location.state 携带进入详情时所在列表流的 id 快照
  // （HomePage.openDetail 与 UpNextList 行两个叠加组入口传入；其余入口/直达/
  // 刷新无 state → nav=null，导航钮与查看器切换不渲染）。index 以 URL 当前
  // 资产在快照中的实际位置为准（浏览器前进/后退逐条恢复各自 state，天然对齐；
  // 对不上一律按无上下文处理，不猜）
  const { search, state: locationState } = useLocation()
  const navigate = useNavigate()
  const nav = useMemo(() => {
    if (!assetId) return null
    const snap = readAssetNavState(locationState)
    if (!snap) return null
    const index = snap.ids.indexOf(assetId)
    return index >= 0 ? { ...snap, index } : null
  }, [assetId, locationState])

  // 换件唯一回调（分页钮与查看器横滑/箭头共用）：保查询串（E1 叠加组底衬
  // 同流约定）+ 带更新过 index 的 state（后续换件与浏览器返回的上下文续接）；
  // 边界停止不循环（待拍板 #12/#18 先行口径：可逆低成本）
  const goNeighbor = useCallback(
    (delta: -1 | 1) => {
      if (!nav) return
      const target = nav.index + delta
      if (target < 0 || target >= nav.ids.length) return
      const nextState: AssetNavState = { ids: nav.ids, index: target, origUrls: nav.origUrls }
      navigate(assetDetailWithSearch(nav.ids[target], search), { state: nextState })
    },
    [nav, navigate, search],
  )

  // 相邻预载直链：按 PRELOAD_AROUND 从快照 origUrls 切片（现有列表类型无该
  // 字段 → 空数组 = 邻项不预载，切换时走详情接口 origUrl 的短暂加载态）
  const preloadUrls = useMemo(() => {
    if (!nav?.origUrls) return []
    const urls: Array<string | undefined> = []
    for (let delta = -PRELOAD_AROUND; delta <= PRELOAD_AROUND; delta++) {
      if (delta === 0) continue
      urls.push(nav.origUrls[nav.index + delta])
    }
    return urls.filter((u): u is string => !!u)
  }, [nav])

  // E5 图片查看器：仅图片/动图媒体可开（视频不接查看器）；换件后 viewer 保持
  // 打开，src 随 detail.origUrl 更新（组件内部按 src 重挂复位手势态）
  const [viewerOpen, setViewerOpen] = useState(false)

  // E1 叠加层滚动自管：详情→详情（右栏换资产）时回顶。旧版本 pathname 变化
  // 由 AppShell 统一 reset .content，叠加化后 .content 复位已对叠加组加门
  // （保底衬列表位置），叠加层自身滚动改由本页在资产切换时归零。挂载即跑
  // 一次无副作用（新叠加层本就在顶部）。
  const overlayRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    overlayRef.current?.scrollTo(0, 0)
  }, [assetId])

  if (!d) {
    // 仅直达冷启动（无任何缓存/占位数据）可达：换件期间 useAssetDetail 的
    // keepPreviousData 占位不会走到这里（不闪「加载中…」）
    return (
      <div className="page asset-overlay" id="page-asset">
        <p className="grid-empty">加载中…</p>
      </div>
    )
  }

  const isVideo = d.mediaType === 'video'

  return (
    <div ref={overlayRef} className="page asset-overlay" id="page-asset">
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
              // E5：图片/动图包裹点击态，点击打开自研查看器（原件直链直显，
              // 不新发签名请求；手势/缩放/切换见 components/media/image-viewer.tsx）
              <button
                type="button"
                className="asset-img-open"
                onClick={() => setViewerOpen(true)}
                title="查看大图"
              >
                <img src={d.origUrl} alt={d.fileName} loading="eager" />
              </button>
            ) : (
              <p className="grid-empty">无法加载内容</p>
            )}
          </div>
          {/* E5 批次导航（媒体区旁）：有列表上下文才渲染；n/总数 序号 +
              上一件/下一件，边界置灰停止不循环；与查看器共用 goNeighbor */}
          {nav ? (
            <div className="asset-pager">
              <button
                type="button"
                className="asset-pager__btn"
                disabled={nav.index <= 0}
                onClick={() => goNeighbor(-1)}
              >
                <ChevronLeft size={14} />
                上一件
              </button>
              <span className="asset-pager__count">
                {nav.index + 1} / {nav.ids.length}
              </span>
              <button
                type="button"
                className="asset-pager__btn"
                disabled={nav.index >= nav.ids.length - 1}
                onClick={() => goNeighbor(1)}
              >
                下一件
                <ChevronRight size={14} />
              </button>
            </div>
          ) : null}
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
            {/* B-1 文件管理操作化：整理（移动/重命名）+ 移入回收站。
                挂详情页的原因与待拍板口径见 FileOpsButton 头注释 */}
            <FileOpsButton asset={d} />
          </div>
          <AssetTagRow assetId={assetId!} tags={d.tags ?? []} />
        </div>
        <aside className="detail-side">
          <AuthorCard authors={d.authors ?? []} />
          <UpNextList assetId={assetId!} mediaType={d.mediaType} cosWork={d.cosWork} />
        </aside>
      </div>
      {/* E5 查看器覆盖层：portal 挂 body（z-index 35 层级位见组件头注释）。
          条件：非视频（视频不接查看器——横滑进视频资产时自动收起）、仍有
          origUrl。换件回调与分页钮共用 goNeighbor，边界外不注入回调即无切换 UI */}
      {viewerOpen && !isVideo && d.origUrl ? (
        <ImageViewer
          src={d.origUrl}
          alt={d.fileName ?? ''}
          onClose={() => setViewerOpen(false)}
          onPrev={nav && nav.index > 0 ? () => goNeighbor(-1) : undefined}
          onNext={nav && nav.index < nav.ids.length - 1 ? () => goNeighbor(1) : undefined}
          position={nav ? { index: nav.index + 1, total: nav.ids.length } : undefined}
          preloadUrls={preloadUrls}
        />
      ) : null}
    </div>
  )
}
