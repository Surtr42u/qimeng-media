import { useCallback, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { InfiniteTail } from '@/components/media/InfiniteTail'
import { ChevronDownIcon } from '@/components/shell/icons'
import { PanelFilters } from '@/components/filters/PanelFilters'
import { Pill } from '@/components/ui/pill'
import { LoadingHint } from '@/components/ui/loading-hint'
import { useAutoMore } from '@/hooks/use-auto-more'
import { panelAssetParams, usePanelFilters } from '@/hooks/use-panel-filters'
import {
  assetToCard,
  useAssetsInfinite,
  useAssetsTotal,
  type AssetListParams,
  type MediaType,
} from '@/hooks/use-assets'
import { DEFAULT_PAGE_SIZE } from '@/lib/constants'
import { isPanelActive } from '@/lib/panel-filters'
import { assetDetail } from '@/lib/route-keys'
import { partitionKey } from '@/lib/search-mapping'
import { PARTITION_OPTIONS, newSearchPageState, type SearchPageState } from './search-state'

/**
 * 搜索结果页（原型 #page-search 移植）：顶栏搜索框回车进入，q 变化整体重置筛选。
 * 数据源 = GET /assets 全参数（q/类型/排序/顺位/区间/年份/标签全部真实传参，阶段 B 接真）；
 * 类型 tab 四档：综合（不传 mediaType）/视频/动图/图片——协议无 audio 类型且数据库无音频
 * 记录（旧 App 的音频档在数据模型里本就无实体，原型残留，见 DOMAIN_RULES 类型口径）。
 * 排序行已删（2026-09-17 用户拍板：原「综合排序/最多点击」两档撤除，排序三档
 * 默认/观看次数/文件大小 收进「更多筛选」面板首行，与相册页共用同一面板组件）；
 * 原 .s-toolbar 工具行一并消亡，「更多筛选」钮并入分区胶囊行行尾（与相册页维度行同构）。
 */

/** 类型 tab 文案 → 协议 MediaType（「综合」不传；MediaType 枚举 image/animated_image/video，无 audio） */
const TYPE_TO_MEDIA: Record<string, MediaType | undefined> = {
  综合: undefined,
  视频: 'video',
  动图: 'animated_image',
  图片: 'image',
}

export default function SearchPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const q = (searchParams.get('q') ?? '').trim()
  // 页面特有维（分区/类型 tab）本地持有；面板共用维走 usePanelFilters（两页同构）
  const [page, setPage] = useState<SearchPageState>(newSearchPageState)
  const panel = usePanelFilters()
  const [panelOpen, setPanelOpen] = useState(false)

  // 数据源：标签池（全量）由 hook 持有；类型徽标计数（limit=1 取 totalMatched，跟随分区口径）
  const pk = partitionKey(page.partition)
  const { data: totalVideo = 0 } = useAssetsTotal('video', pk)
  const { data: totalAnimated = 0 } = useAssetsTotal('animated_image', pk)
  const { data: totalImage = 0 } = useAssetsTotal('image', pk)

  // 新搜索词整体重置：React 官方「渲染期调整状态」模式（同 CollectionPage
  // 作者切换重置的先例）——重置发生在 q 变化的同一帧渲染内。此前 useEffect
  // 版本首帧会先以「旧筛选 + 新 q」发一针过渡请求再换键重取（2026-09-20
  // 全库审查清偿：每次换词多一次无效请求 + 过渡期列表按旧筛选收窄闪烁）。
  const [prevQ, setPrevQ] = useState(q)
  if (prevQ !== q) {
    setPrevQ(q)
    setPage(newSearchPageState())
    setPanelOpen(false)
    panel.reset()
  }

  // 稳定打开回调：MediaCard 已 memo 化，onOpen 引用稳定才能让浅等比较生效
  const openCard = useCallback(
    (id?: string) => {
      if (id) navigate(assetDetail(id))
    },
    [navigate],
  )

  const setPageField = <K extends keyof SearchPageState>(key: K, value: SearchPageState[K]) =>
    setPage((s) => ({ ...s, [key]: value }))

  /** 筛选状态 → GET /assets 参数（唯一组装点；q 为空时页码区不发起查询） */
  const listParams = useMemo<AssetListParams>(() => {
    const p: AssetListParams = { q, limit: DEFAULT_PAGE_SIZE, ...panelAssetParams(panel.state) }
    // 分区三态（DOMAIN_RULES §6）：常规=不传（默认排除 COS，历史口径）、
    // COS=cosOnly、全部=includeCos；服务端 cosOnly 优先于 includeCos。
    if (page.partition === 'COS') p.cosOnly = true
    else if (page.partition === '全部') p.includeCos = true
    const mt = TYPE_TO_MEDIA[page.type]
    if (mt) p.mediaType = mt
    return p
  }, [q, page, panel.state])

  const { data: pages, isLoading, isFetchingNextPage, isPlaceholderData, fetchNextPage, hasNextPage } = useAssetsInfinite(listParams, q !== '')
  const items = useMemo(() => pages?.pages.flatMap((pg) => pg.items ?? []) ?? [], [pages])

  // E3 无感加载哨兵：enabled 与原 pill 的 when 同口径（q==='' 不挂不拉）。
  // onHit 双守卫：isFetchingNextPage 防重复拉页；isPlaceholderData 前瞻防混拼
  // （useAssetsInfinite 已配 keepPreviousData，换词占位期间守卫真实生效）。
  const sentinelRef = useAutoMore(q !== '' && hasNextPage, () => {
    if (!isFetchingNextPage && !isPlaceholderData) void fetchNextPage()
  })

  // 类型 tab 结构（综合无徽标照原型；徽标 = 该类型资产总数，与当前筛选无关）
  const typeTabs = useMemo(
    () => [
      { label: '综合', total: undefined },
      { label: '视频', total: totalVideo },
      { label: '动图', total: totalAnimated },
      { label: '图片', total: totalImage },
    ],
    [totalVideo, totalAnimated, totalImage],
  )

  return (
    <div className="page" id="page-search">
      <div className="stype-row">
        {typeTabs.map((t) => (
          <button
            key={t.label}
            type="button"
            className={`stype${page.type === t.label ? ' active' : ''}`}
            data-stype={t.label}
            onClick={() => setPageField('type', t.label)}
          >
            {t.label}
            {/* 「综合」无计数徽标（照原型） */}
            {t.total !== undefined ? <span className="stype-count">{t.total}</span> : null}
          </button>
        ))}
      </div>
      {/* 分区胶囊与「更多筛选」钮同行（2026-09-17 用户拍板：胶囊和更多筛选一行，
          与相册页维度行同构；原独立 .s-toolbar 工具行随排序行一并消亡）：
          全部=includeCos、常规=不传（DOMAIN_RULES §6 隔离口径，主动切换才排除）、
          COS=cosOnly。类型行仍是首行对齐锚（原型对齐规则：类型行中心
          ↔首页项中心）；横向 24px 贴线（规则 1），行距交给 .page gap 不加额外 padding */}
      <div className="pill-row" role="group" aria-label="内容分区" style={{ padding: '0 24px' }}>
        {PARTITION_OPTIONS.map((pt) => (
          <Pill key={pt} active={page.partition === pt} onClick={() => setPageField('partition', pt)}>
            {pt}
          </Pill>
        ))}
        <button
          type="button"
          className={`more-filter${panelOpen ? ' open' : ''}${isPanelActive(panel.state) ? ' active' : ''}`}
          aria-expanded={panelOpen}
          onClick={() => setPanelOpen((v) => !v)}
        >
          更多筛选
          <ChevronDownIcon />
        </button>
      </div>
      <PanelFilters
        hidden={!panelOpen}
        state={panel.state}
        tagPool={panel.tagPool}
        tagInputOpen={panel.tagInputOpen}
        setFilter={panel.setFilter}
        onToggleTag={panel.toggleTag}
        onRemoveTag={panel.removeTag}
        onOpenTagInput={panel.openTagInput}
        onCloseTagInput={panel.closeTagInput}
        onAddTag={panel.addTag}
      />
      <div className="grid">
        {q === '' ? (
          <p className="grid-empty">在顶部搜索框输入关键词开始搜索</p>
        ) : items.length ? (
          items.map((a) => (
            <MediaCard key={a.id} {...assetToCard(a)} onOpen={openCard} />
          ))
        ) : isLoading ? (
          <LoadingHint />
        ) : (
          <p className="grid-empty">没有匹配的内容，放宽一点筛选条件试试。</p>
        )}
      </div>
      {/* E3 无感加载尾部（三件套见 components/media/InfiniteTail）；哨兵
          仅在有搜索词（列表可能非空）时挂载 */}
      <InfiniteTail
        isFetchingNextPage={isFetchingNextPage}
        hasNextPage={hasNextPage}
        itemCount={items.length}
        sentinelRef={sentinelRef}
        sentinelActive={q !== ''}
      />
    </div>
  )
}
