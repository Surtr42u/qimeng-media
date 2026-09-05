import { QM_REFRESH_EVENT } from '@/lib/constants'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import type { AssetSummary } from '@/api/generated'
import { MediaCard } from '@/components/media/MediaCard'
import { ContentRankGrid } from '@/components/data/ContentRankGrid'
import { assetToCard, useRecommendations } from '@/hooks/use-assets'
import { useRankingsInfinite } from '@/hooks/use-stats'
import { useAutoMore } from '@/hooks/use-auto-more'
import { parseRankPeriod, type HomeRankPeriod, type HomeTabKey } from '@/lib/home-tabs'

/**
 * 首页：顶栏分类 tab（推荐/cos/排行榜）对应的内容区（tab 由 URL ?tab= 驱动，
 * TopBar 只写、本页只读——刷新/直达不丢态）。
 *   - recommend（缺省）：推荐卡片流（GET /recommendations，M3 十维推荐算法，
 *     常规流不含 COS——DOMAIN_RULES §6 隔离）；
 *   - cos：COS 推荐模式（GET /recommendations?cosOnly=true——旧版首页 COS
 *     tab 语义，同一套十维打分/每日惩罚跑在 COS 子集上，seed 换一批）；
 *   - hot：内容榜（GET /rankings，?period= 周期由顶栏周期行写入，缺省日榜；
 *     纯热度口径 view+play+like，DOMAIN_RULES §2）。
 * 三个 tab 统一「滚动触底自动加载下一页」（use-auto-more，对齐旧版
 * 「距底部 ≤6 项提前增量加载、不重排」）；AppShell 刷新按钮广播的
 * qm:refresh 收到后重排回第一页（旧版 refreshSeed++ 全量重排语义）。
 * 卡片点击进详情（图片大图/视频播放）。
 */

/** 首页触底加载页大小（协议 limit 默认值；与 useRecommendations 缺省一致） */
const HOME_PAGE_SIZE = 60

export default function HomePage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const tab: HomeTabKey = (searchParams.get('tab') as HomeTabKey) ?? 'recommend'
  const period = parseRankPeriod(searchParams.get('period'))

  const openDetail = (id?: string): void => {
    if (id) navigate(`/app/asset/${id}`)
  }

  return (
    <div className="page" id="page-home">
      {tab === 'hot' ? (
        <HotRankTab period={period} onOpen={openDetail} />
      ) : (
        <div className="grid">
          {tab === 'recommend'
            ? <RecommendTab onOpen={openDetail} />
            : <CosRecommendTab onOpen={openDetail} />}
        </div>
      )}
    </div>
  )
}

/**
 * 订阅 AppShell 刷新按钮广播的 'qm:refresh'（事件名是与 AppShell 的契约，
 * 一字不改）。旧版 refreshSeed++ 语义的入口：回调里只做「换缓存键」——
 * 推荐/cos 换 seed（全量重排并回第一页）、排行榜换 reloadKey（重置分页
 * 重拉），具体由各 tab 自行决定。
 */
function useQmRefresh(onRefresh: () => void): void {
  const handlerRef = useRef(onRefresh)
  useEffect(() => {
    handlerRef.current = onRefresh
  })
  useEffect(() => {
    const fn = (): void => handlerRef.current()
    window.addEventListener(QM_REFRESH_EVENT, fn)
    return () => window.removeEventListener(QM_REFRESH_EVENT, fn)
  }, [])
}

/**
 * 渲染前按 assetId 去重：推荐流每次请求按当下打分重排切片（协议 offset
 * 描述），同流第二页可能混入第一页已见项——旧版本地列表一次拉全量天然
 * 无重复，web 用过滤近似。排行榜热度序跨请求稳定，但两次取页之间计数
 * 也可能变化，统一走同一过滤（重复项消失而非重排，语义安全）。
 * 注意：去重只发生在渲染层，hasNextPage 判断必须用服务端原始页长度。
 */
function dedupeByAssetId(pages: AssetSummary[][]): AssetSummary[] {
  const seen = new Set<string>()
  const out: AssetSummary[] = []
  for (const page of pages) {
    for (const a of page) {
      if (a.id === undefined) {
        out.push(a) // 协议 id 可空：无 id 的行无从判重，原样保留
        continue
      }
      if (!seen.has(a.id)) {
        seen.add(a.id)
        out.push(a)
      }
    }
  }
  return out
}

/** 推荐/cos 共用的触底增量流：useInfiniteQuery 组装（pageParam=offset，
 *  页大小 60）+ 渲染前去重 + 触底哨兵。seed/cosOnly 进 queryKey——
 *  seed 变化即整条流重置回第一页（换一批与刷新共用此机制）。 */
function useRecommendationStream(seed: number, cosOnly: boolean) {
  const q = useRecommendations(HOME_PAGE_SIZE, seed, cosOnly)
  const items = useMemo(() => dedupeByAssetId(q.data?.pages ?? []), [q.data])
  const sentinelRef = useAutoMore(q.hasNextPage, () => {
    if (!q.isFetchingNextPage) void q.fetchNextPage()
  })
  return {
    items,
    isLoading: q.isLoading,
    isFetchingNextPage: q.isFetchingNextPage,
    hasNextPage: q.hasNextPage,
    sentinelRef,
  }
}

type RecommendationStream = ReturnType<typeof useRecommendationStream>

/** 卡片流渲染：卡片 + 加载中/到底提示（复用 grid-empty/pill 类）+ 触底
 *  哨兵。哨兵只在还有下一页时挂载——到底即卸载，观察器随之断开。 */
function StreamCards({ stream, onOpen, emptyHint, footer, endHint }: {
  stream: RecommendationStream
  onOpen: (id?: string) => void
  emptyHint: string
  /** 常驻页脚（cos：换一批 + 计数），首屏加载完即显示 */
  footer?: ReactNode
  /** 到底提示（无下一页且非空时显示） */
  endHint?: ReactNode
}) {
  const { items, isLoading, isFetchingNextPage, hasNextPage, sentinelRef } = stream
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
      {isFetchingNextPage && <p className="grid-empty">加载中…</p>}
      {!isLoading && items.length === 0 && <p className="grid-empty">{emptyHint}</p>}
      {!isLoading && items.length > 0 && footer}
      {!isLoading && items.length > 0 && !hasNextPage && endHint}
      {hasNextPage && <div ref={sentinelRef} style={{ height: 1 }} aria-hidden="true" />}
    </>
  )
}

/** 推荐 tab（recommend）：卡片流触底增量加载；qm:refresh → seed=Date.now()
 *  （旧版 refreshSeed++：全量重排并回第一页）。 */
function RecommendTab({ onOpen }: { onOpen: (id?: string) => void }) {
  const [seed, setSeed] = useState(0)
  useQmRefresh(() => setSeed(Date.now()))
  const stream = useRecommendationStream(seed, false)
  return (
    <StreamCards
      stream={stream}
      onOpen={onOpen}
      emptyHint="还没有内容——先到「维护 → 文件管理」注册一个媒体库并扫描。"
      endHint={<p className="grid-empty">到底了</p>}
    />
  )
}

/** COS 推荐 tab（cos）：旧版「COS 推荐模式」——同套算法跑 COS 子集，
 *  触底增量加载；换一批 = seed 换键重排（与服务端每日展示惩罚自然衔接），
 *  qm:refresh 同语义。 */
function CosRecommendTab({ onOpen }: { onOpen: (id?: string) => void }) {
  const [seed, setSeed] = useState(0)
  useQmRefresh(() => setSeed(Date.now()))
  const stream = useRecommendationStream(seed, true)
  return (
    <StreamCards
      stream={stream}
      onOpen={onOpen}
      emptyHint="暂无 COS 内容——先到「维护 → 文件管理」注册 COS 媒体库并扫描。"
      footer={(
        <p className="grid-empty">
          <button
            className="pill"
            type="button"
            onClick={() => setSeed(Date.now())}
          >
            换一批
          </button>
          <span className="pill-count">
            共 {stream.items.length} 项{stream.hasNextPage ? '' : ' · 到底了'}
          </span>
        </p>
      )}
    />
  )
}

/** 排行榜 tab（hot）：同触底加载。period 变化经 queryKey 换档天然重置
 *  分页；qm:refresh → reloadKey+1 重置分页重拉（排行榜是确定性排序，
 *  不需要 seed 打散）。 */
function HotRankTab({ period, onOpen }: { period: HomeRankPeriod; onOpen: (id?: string) => void }) {
  const [reloadKey, setReloadKey] = useState(0)
  useQmRefresh(() => setReloadKey((n) => n + 1))
  const q = useRankingsInfinite(period, HOME_PAGE_SIZE, reloadKey)
  const items = useMemo(() => dedupeByAssetId(q.data?.pages ?? []), [q.data])
  const sentinelRef = useAutoMore(q.hasNextPage, () => {
    if (!q.isFetchingNextPage) void q.fetchNextPage()
  })
  return (
    <div className="rank-card">
      <div className="rank-head">
        <h3>内容榜</h3>
        <span className="rank-note">按浏览量</span>
      </div>
      {q.isLoading ? (
        <p className="grid-empty">加载中…</p>
      ) : (
        <ContentRankGrid items={items} onOpen={(a) => onOpen(a.id)} />
      )}
      {q.isFetchingNextPage && <p className="grid-empty">加载中…</p>}
      {!q.isLoading && items.length > 0 && !q.hasNextPage && (
        <p className="grid-empty">到底了</p>
      )}
      {q.hasNextPage && <div ref={sentinelRef} style={{ height: 1 }} aria-hidden="true" />}
    </div>
  )
}
