import { DEFAULT_PAGE_SIZE, QM_REFRESH_EVENT } from '@/lib/constants'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import type { AssetSummary } from '@/api/generated'
import { MediaCard } from '@/components/media/MediaCard'
import { assetToCard, useRecommendations } from '@/hooks/use-assets'
import { useRankingsInfinite } from '@/hooks/use-stats'
import { useAutoMore } from '@/hooks/use-auto-more'
import { parseRankPeriod, type HomeRankPeriod, type HomeTabKey } from '@/lib/home-tabs'
import { assetDetailWithSearch, type AssetNavState } from '@/lib/route-keys'

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

export default function HomePage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const tab: HomeTabKey = (searchParams.get('tab') as HomeTabKey) ?? 'recommend'
  const period = parseRankPeriod(searchParams.get('period'))

  // E5：nav = 当前已加载流的 id 快照 + 所点下标（StreamCards 组装），经
  // location.state 交给详情页做上一件/下一件批次导航；无则按无上下文打开
  const openDetail = (id?: string, nav?: AssetNavState): void => {
    if (!id) return
    // E1 叠加打开携带当前查询串（tab/period）：详情期间底衬 HomePage 保持
    // 同一条流（tab 子组件不换挂、换一批 seed 不丢），浏览器返回的历史条目
    // 与离开时完全一致。与详情右栏 upnext 行（UpNextList）共用统一入口
    // assetDetailWithSearch；recommend 缺省无参数则不加 ?（空查询串不污染 URL）。
    const q = searchParams.toString()
    navigate(assetDetailWithSearch(id, q ? `?${q}` : ''), nav ? { state: nav } : undefined)
  }

  return (
    <div className="page" id="page-home">
      {/* F7（2026-09-08）：grid 容器下沉进各 tab（StreamCards 持有）——网格只放
          卡片，「加载中/空态/到底了/换一批」等提示行改为网格之下的全宽独立行
          （此前 endHint 作为 .grid 子元素占一个网格格位与缩略图并列，实测
          rect 宽=单格宽）。热榜带 grid--hot 修饰类（首行贴侧栏节奏的热榜例外，
          见 prototype.css），由 HotRankTab 经 gridClassName 传入。 */}
      {tab === 'hot'
        ? <HotRankTab period={period} onOpen={openDetail} />
        : tab === 'recommend'
          ? <RecommendTab onOpen={openDetail} />
          : <CosRecommendTab onOpen={openDetail} />}
    </div>
  )
}

/**
 * 订阅 AppShell 刷新按钮广播的 qm:refresh（事件名以 QM_REFRESH_EVENT 常量
 * 为单一来源，派发侧 AppShell 同源引用，见 constants.ts）。旧版 refreshSeed++
 * 语义的入口：回调里只做「换缓存键」——推荐/cos 换 seed（全量重排并回第一页）、
 * 排行榜换 reloadKey（重置分页重拉），具体由各 tab 自行决定。
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
  const q = useRecommendations(DEFAULT_PAGE_SIZE, seed, cosOnly)
  const items = useMemo(() => dedupeByAssetId(q.data?.pages ?? []), [q.data])
  const sentinelRef = useAutoMore(q.hasNextPage, () => {
    if (!q.isFetchingNextPage) void q.fetchNextPage()
  })
  return {
    items,
    isLoading: q.isLoading,
    isFetchingNextPage: q.isFetchingNextPage,
    hasNextPage: q.hasNextPage,
    // P3.1（E1 返工）：换 seed 的占位窗口里 hasNextPage 取自旧流（可能为
    // false）——「到底了」类文案须配合此标记抑制，防窗口期闪现
    isPlaceholderData: q.isPlaceholderData,
    // F2：换键重取失败透传给 StreamCards 显示错误态（hook 原样返回
    // useInfiniteQuery 结果，isError/refetch 天然可得，无需 hook 层改动）
    isError: q.isError,
    refetch: q.refetch,
    sentinelRef,
  }
}

type RecommendationStream = ReturnType<typeof useRecommendationStream>

/** 卡片流渲染：卡片 + 加载中/到底提示（复用 grid-empty/pill 类）+ 触底
 *  哨兵。哨兵只在还有下一页时挂载——到底即卸载，观察器随之断开。
 *  F7：本组件持有 .grid 容器（ HomePage 不再包网格）——网格内只渲染卡片，
 *  全部提示行（加载中/空态/页脚/到底了）与哨兵移到网格之下作全宽独立行
 *  （.page 为纵向 flex，直挂子元素自动撑满内容区宽），不再与缩略图抢格位。 */
function StreamCards({ stream, onOpen, emptyHint, footer, endHint, gridClassName = 'grid' }: {
  stream: RecommendationStream
  onOpen: (id?: string, nav?: AssetNavState) => void
  emptyHint: string
  /** 常驻页脚（cos：换一批 + 计数），首屏加载完即显示 */
  footer?: ReactNode
  /** 到底提示（无下一页且非空时显示） */
  endHint?: ReactNode
  /** 网格容器类（热榜传 'grid grid--hot'，缺省 'grid'） */
  gridClassName?: string
}) {
  const { items, isLoading, isFetchingNextPage, hasNextPage, isPlaceholderData, isError, refetch, sentinelRef } = stream
  // E5 批次导航快照：当前已渲染（去重后）流的 id 序与所点下标。协议 id 可空：
  // 无 id 项不可跳详情也不入快照（快照与可点项保持同序同集）
  const navContext = (() => {
    const ids: string[] = []
    const indexAt = new Map<string, number>()
    for (const a of items) {
      if (a.id === undefined) continue
      indexAt.set(a.id, ids.length)
      ids.push(a.id)
    }
    return { ids, indexAt }
  })()
  return (
    <>
      <div className={gridClassName}>
        {items.map((a) => (
          <MediaCard
            key={a.id}
            {...assetToCard(a)}
            onClick={() => {
              if (a.id === undefined) {
                onOpen(undefined)
                return
              }
              onOpen(a.id, { ids: navContext.ids, index: navContext.indexAt.get(a.id) ?? 0 })
            }}
          />
        ))}
      </div>
      {/* 提示行区（网格之下全宽直挂）：加载中/错误/空态/页脚/到底了/哨兵。
          F2：错误态优先于空态——换键重取失败（isError）时不再落进 emptyHint
          （「暂无上榜内容」类文案把失败误报成没数据）；三个流（推荐/cos/热榜）
          经同一 StreamCards 出口天然同口径。v5 实际语义（P2-1 注释修正）：
          占位只在 pending 态生效——换键失败期占位被丢弃（status=error、
          data=undefined）→ 网格清空，只剩「加载失败+重试」；fetchNextPage
          同键失败才保留已载卡片，页脚/到底了让位错误行，恢复路径都是「重试」
          一颗钮（错误期哨兵卸载，不再自动续拉）。样式复用 pill/grid-empty
          既有 token，零新颜色字面量 */}
      {isLoading && <p className="grid-empty">加载中…</p>}
      {!isError && isFetchingNextPage && <p className="grid-empty">加载中…</p>}
      {isError ? (
        <p className="grid-empty">
          加载失败
          <button className="pill" type="button" onClick={() => void refetch()}>
            重试
          </button>
        </p>
      ) : (
        <>
          {!isLoading && items.length === 0 && <p className="grid-empty">{emptyHint}</p>}
          {!isLoading && items.length > 0 && footer}
          {/* P3.1：占位窗口（换 seed 重取中）hasNextPage 是旧流的，可能假性
              false——到底提示须等新流到货再判 */}
          {!isLoading && !isPlaceholderData && items.length > 0 && !hasNextPage && endHint}
        </>
      )}
      {hasNextPage && !isError && <div ref={sentinelRef} style={{ height: 1 }} aria-hidden="true" />}
    </>
  )
}

/** 推荐 tab（recommend）：卡片流触底增量加载；qm:refresh → seed=Date.now()
 *  （旧版 refreshSeed++：全量重排并回第一页）。 */
function RecommendTab({ onOpen }: { onOpen: (id?: string, nav?: AssetNavState) => void }) {
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
function CosRecommendTab({ onOpen }: { onOpen: (id?: string, nav?: AssetNavState) => void }) {
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
            共 {stream.items.length} 项
            {/* P3.1：占位窗口 hasNextPage 取自旧流，「到底了」后缀同 endHint 口径抑制 */}
            {stream.hasNextPage || stream.isPlaceholderData ? '' : ' · 到底了'}
          </span>
        </p>
      )}
    />
  )
}

/** 排行榜 tab（hot）：排版与推荐完全一致（MediaCard 卡片流 + 触底增量加载，
 *  原 rank-card「内容榜」标题壳按用户拍板去除）。period 变化经 queryKey
 *  换档天然重置分页；qm:refresh → reloadKey+1 重置分页重拉（排行榜是
 *  确定性排序，不需要 seed 打散）。 */
function HotRankTab({ period, onOpen }: { period: HomeRankPeriod; onOpen: (id?: string, nav?: AssetNavState) => void }) {
  const [reloadKey, setReloadKey] = useState(0)
  useQmRefresh(() => setReloadKey((n) => n + 1))
  const q = useRankingsInfinite(period, DEFAULT_PAGE_SIZE, reloadKey)
  const items = useMemo(() => dedupeByAssetId(q.data?.pages ?? []), [q.data])
  const sentinelRef = useAutoMore(q.hasNextPage, () => {
    if (!q.isFetchingNextPage) void q.fetchNextPage()
  })
  const stream: RecommendationStream = {
    items,
    isLoading: q.isLoading,
    isFetchingNextPage: q.isFetchingNextPage,
    hasNextPage: q.hasNextPage,
    // E2：榜单流已配 keepPreviousData——period 换档/qm:refresh 换 reloadKey
    // 重取期间旧榜保留占位，isPlaceholderData 为 true，与推荐流共用口径
    isPlaceholderData: q.isPlaceholderData,
    // F2：错误态透传，与推荐流同口径（见 useRecommendationStream 注释）
    isError: q.isError,
    refetch: q.refetch,
    sentinelRef,
  }
  return (
    <StreamCards
      stream={stream}
      onOpen={onOpen}
      gridClassName="grid grid--hot"
      emptyHint="暂无上榜内容。"
      endHint={<p className="grid-empty">到底了</p>}
    />
  )
}
