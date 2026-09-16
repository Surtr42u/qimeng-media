import { useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import type { AssetSummary } from '@/api/generated'
import { MediaCard } from '@/components/media/MediaCard'
import { InfiniteTail } from '@/components/media/InfiniteTail'
import { Pill } from '@/components/ui/pill'
import { ChevronDownIcon } from '@/components/shell/icons'
import { DEFAULT_PAGE_SIZE, LOCALE_ZH } from '@/lib/constants'
import { groupAlbumsByDate } from '@/lib/album-grouping'
import { ALBUMS_PATH, assetDetailWithSearch, type OverlayDetailState } from '@/lib/route-keys'
import { useAutoMore } from '@/hooks/use-auto-more'
import {
  assetToCard,
  useAssetsInfinite, useAssetFacets, facetToOptions,
  type FacetOptionKind,
  type AssetSort, type MediaType, type Partition,
} from '@/hooks/use-assets'

/**
 * 相册页（原型 #page-albums 移植，阶段 B 四维聚合已接真实数据）：
 * 四维胶囊筛选（分区/作者/角色/类型，GET /assets/facets 排自身口径）+ 内容网格。
 * 语义对齐全部分区 = 旧版「全部」tab 的四行胶囊（用户决策 2026-09-03）：
 *   - 分区 = all/regular/cos 三态；缺省 = 全部（旧版「全部」tab 默认分区，
 *     常规与 COS 同流展示；regular/cos 用于隔离浏览，DOMAIN_RULES §6）；
 *   - 作者 = 常规出处分组（kind=source，含「其他」桶；=旧版「作品」行的
 *     groupBySource）∪ COS 作者（kind=author，=groupByCosAuthor）——按分区
 *     合并：常规分区只有出处、COS 分区只有 COS 作者、全部分区两者合并；
 *     常规 TXT 作者表行不进作者栏。胶囊按 kind 分派筛选参数
 *     （source / authorId 同属作者行，二选一）；
 *   - 角色 = 常规分区=匹配引擎角色名（kind=character）、COS 分区=COS 作品名
 *     （kind=work，=旧版「COS 角色=作品名」），全部分区两者合并；胶囊按
 *     kind 分派 character / work（两参数同属角色行，二选一）；
 *   - 类型 = MediaType 四档（含全部）。
 * 计数口径：每个维的候选计数都"排自身"（忽略该维自身选择）——四个 facets
 * 请求各缺一个自己的参数，任何一维的徽标/值行都是排自身计数。
 * 排序映射（文案=原型）：精选=default / 最新=fileDate desc / 最旧=fileDate asc /
 * 按名称=name asc。
 * 网格时间分区（原型 #5）：口径单源 lib/album-grouping.ts（按 modifiedAt 的
 * dateLabel 分组、组间按组首时间降序）；胶囊切换只改变 items，
 * 分组是其上的纯函数。
 */

type DimKey = 'partition' | 'author' | 'character' | 'type'

/** 行内选中形态：作者行 kind=source|author、角色行 kind=character|work 二选一 */
type RowSel = { kind: FacetOptionKind; value: string } | null

const DIM_LABELS: Record<DimKey, string> = {
  partition: '分区',
  author: '作者',
  character: '角色',
  type: '类型',
}

/** 排序档（文案与原型一致）→ 协议 sort/order 参数 */
/** 排序档（2026-09-17 用户拍板对齐手机任务1三档：默认/观看次数/文件大小；收进「更多筛选」面板首行） */
const SORTS: { label: string; sort: AssetSort; order: 'asc' | 'desc' }[] = [
  { label: '默认', sort: 'default', order: 'desc' },
  { label: '观看次数', sort: 'viewCount', order: 'desc' },
  { label: '文件大小', sort: 'sizeBytes', order: 'desc' },
]

/** 值行超过该数量（含「全部」）默认收起两行（原型交互阈值） */
const VALUE_COLLAPSE_THRESHOLD = 9

/** 分区缺省 = 全部：相册页即旧版「全部」tab（COS 独立入口同流，
 *  隔离浏览走 常规/COS 分区胶囊） */
const DEFAULT_PARTITION: Partition = 'all'

export default function AlbumsPage() {
  const navigate = useNavigate()

  // 选择状态（四维互不重置；切分区清作者/角色——两行候选的 kind 命名空间
  // 随分区变化：常规=出处/角色，COS=COS 作者/作品名）
  const [dim, setDimState] = useState<DimKey>('partition')
  const [partition, setPartition] = useState<Partition>(DEFAULT_PARTITION)
  const [authorSel, setAuthorSel] = useState<RowSel>(null)
  const [characterSel, setCharacterSel] = useState<RowSel>(null)
  const [mediaType, setMediaType] = useState<MediaType | null>(null)
  const [expanded, setExpanded] = useState(false)
  // 筛选值行默认收起（2026-09-17 用户拍板「元素1默认收起，最右侧加筛选」）：
  // 有选中值时自动展开并点亮按钮（不做筛选=默认列表），手动开合走 filterOpen
  const [filterOpen, setFilterOpen] = useState(false)
  // 「更多筛选」面板开合（2026-09-17：面板首行=排序三档，形态对齐搜索页同款组件）
  const [panelOpen, setPanelOpen] = useState(false)
  const [sortIdx, setSortIdx] = useState(0)

  const sort = SORTS[sortIdx]

  // 是否有筛选生效（分区「全部」为缺省不算筛选）：有则值行自动展开、筛选钮点亮
  const hasFilter =
    partition !== DEFAULT_PARTITION ||
    authorSel !== null ||
    characterSel !== null ||
    mediaType !== null

  // 作者行选中 → GET /assets 参数（source 与 authorId 二选一）
  const authorParams = useMemo(
    () =>
      authorSel === null
        ? {}
        : authorSel.kind === 'source'
          ? { source: authorSel.value }
          : { authorId: authorSel.value },
    [authorSel],
  )
  // 角色行选中 → 协议参数（character 与 work 二选一）
  const characterParams = useMemo(
    () =>
      characterSel === null
        ? {}
        : characterSel.kind === 'work'
          ? { work: characterSel.value }
          : { character: characterSel.value },
    [characterSel],
  )

  // 内容网格：四维选择 → GET /assets 参数（分区三态开关映射见 hooks 注释）
  const listParams = useMemo(
    () => ({
      ...(partition === 'all' ? { includeCos: true } : partition === 'cos' ? { cosOnly: true } : {}),
      ...authorParams,
      ...characterParams,
      ...(mediaType ? { mediaType } : {}),
      sort: sort.sort,
      order: sort.order,
      limit: DEFAULT_PAGE_SIZE,
    }),
    [partition, authorParams, characterParams, mediaType, sort],
  )

  const {
    data: pages, isFetching, isFetchingNextPage, isPlaceholderData, fetchNextPage, hasNextPage,
  } = useAssetsInfinite(listParams)
  const items = useMemo(() => pages?.pages.flatMap((p) => p.items ?? []) ?? [], [pages])

  // E3 无感加载哨兵（对齐首页 use-auto-more 语义：触底提前 6 项拉下一页）。
  // onHit 双守卫：isFetchingNextPage 防重复拉页；isPlaceholderData 前瞻防混拼
  // （useAssetsInfinite 未配 placeholderData 恒 false，守卫零成本）。
  const sentinelRef = useAutoMore(hasNextPage, () => {
    if (!isFetchingNextPage && !isPlaceholderData) void fetchNextPage()
  })

  // 时间分区（原型 renderAlbumGrid）：口径单源在 lib/album-grouping.ts（ADR-0008
  // 规则抽离）；胶囊/排序切换只改变 items，分组是其上的纯函数。
  const groups = useMemo(() => groupAlbumsByDate(items), [items])

  // F5 批次导航快照 + 叠加打开：照 HomePage StreamCards 的 navContext 组装。
  // 快照序 = 用户看到的平铺序（分组序拼接——组间按组首时间降序、组内保原序），
  // pager 上一件/下一件才与可视顺序一致；协议 id 可空：无 id 项不可跳详情也
  // 不入快照（快照与可点项保持同序同集）。backdrop 顶层独立字段声明底衬归属，
  // 布局据此在详情打开期间保住相册页实例与滚动（缺省回落的是首页）。
  const { search } = useLocation()
  const navContext = useMemo(() => {
    const ids: string[] = []
    const indexAt = new Map<string, number>()
    for (const g of groups) {
      for (const a of g.assets) {
        if (a.id === undefined) continue
        indexAt.set(a.id, ids.length)
        ids.push(a.id)
      }
    }
    return { ids, indexAt }
  }, [groups])

  const openDetail = (id?: string): void => {
    if (!id) return
    // 叠加组导航统一入口 assetDetailWithSearch（search 原样携带，现相册页无
    // 查询串即空串不加 ?；日后相册页做 URL 保态时此处自动续接）
    const navState: OverlayDetailState = {
      ids: navContext.ids,
      index: navContext.indexAt.get(id) ?? 0,
      backdrop: ALBUMS_PATH,
    }
    navigate(assetDetailWithSearch(id, search), { state: navState })
  }

  // 四维 facets：每个请求"缺自身参数"（服务端排自身计数）。
  // partition 恒显式传参（不再依赖服务端缺省=all 的隐式行为）。
  const facetPartition = useAssetFacets({ ...authorParams, ...characterParams, ...(mediaType ? { mediaType } : {}) })
  const facetAuthor = useAssetFacets({ partition, ...characterParams, ...(mediaType ? { mediaType } : {}) })
  const facetCharacter = useAssetFacets({ partition, ...authorParams, ...(mediaType ? { mediaType } : {}) })
  const facetType = useAssetFacets({ partition, ...authorParams, ...characterParams })

  // 「全部」胶囊计数 = 当前其他维度选择下的总文件数（分区栏 all 桶）
  const total = facetPartition.data?.partitions.find((b) => b.key === 'all')?.fileCount

  // 值行候选：作者/角色行在服务端候选前补「全部」胶囊（value=''=清除本行）
  const dimValues: { label: string; value: string; count?: number; kind?: FacetOptionKind }[] = useMemo(() => {
    switch (dim) {
      case 'partition':
        return facetToOptions(facetPartition.data?.partitions ?? [])
      case 'author':
        return [
          { label: '全部', value: '', count: total },
          ...facetToOptions(facetAuthor.data?.authors ?? []),
        ]
      case 'character':
        return [
          { label: '全部', value: '', count: total },
          ...facetToOptions(facetCharacter.data?.characters ?? []),
        ]
      case 'type':
        return facetToOptions(facetType.data?.types ?? [])
    }
  }, [dim, facetPartition.data, facetAuthor.data, facetCharacter.data, facetType.data, total])

  // 当前行激活判定：分区/类型行按 value 匹配；作者/角色行按 value+kind 匹配
  // （出处与 COS 作者、角色与 COS 作品可能同名不同 kind）
  const isActive = (v: { value: string; kind?: FacetOptionKind }): boolean => {
    switch (dim) {
      case 'partition':
        return partition === v.value
      case 'type':
        return (mediaType ?? 'all') === v.value
      case 'author':
        return authorSel !== null && authorSel.value === v.value && authorSel.kind === v.kind
      case 'character':
        return characterSel !== null && characterSel.value === v.value && characterSel.kind === v.kind
    }
  }

  const badgeCount = (d: DimKey): number | undefined => {
    switch (d) {
      case 'partition':
        return Math.max((facetPartition.data?.partitions.length ?? 0) - 1, 0)
      case 'author':
        return facetAuthor.data?.authors.length
      case 'character':
        return facetCharacter.data?.characters.length
      case 'type':
        return Math.max((facetType.data?.types.length ?? 0) - 1, 0)
    }
  }

  const setDim = (next: DimKey): void => {
    setDimState(next)
    setExpanded(false)
  }

  // 维度胶囊 = 手风琴（2026-09-17 用户澄清「点击胶囊会自己弹开，手机的那种」）：
  // 换维度必展开值行；同维度再点一下开合切换。开合按值行当前可见态
  // （filterOpen || hasFilter，与筛选钮箭头同口径）判定；hasFilter 常驻展开时
  // 收起仍走 filterOpen=false，值行是否隐藏由既有可见规则（filterOpen||hasFilter）决定。
  const toggleDim = (next: DimKey): void => {
    if (next !== dim) {
      setDim(next)
      setFilterOpen(true)
      return
    }
    setFilterOpen(!(filterOpen || hasFilter))
  }

  const pickValue = (opt: { value: string; kind?: FacetOptionKind }): void => {
    switch (dim) {
      case 'partition': {
        setPartition(opt.value as Partition)
        setAuthorSel(null) // 作者/角色候选随分区换命名空间（kind 集合变），重置
        setCharacterSel(null)
        break
      }
      case 'author':
        setAuthorSel(opt.value === '' ? null : { kind: opt.kind ?? 'source', value: opt.value })
        break
      case 'character':
        setCharacterSel(opt.value === '' ? null : { kind: opt.kind ?? 'character', value: opt.value })
        break
      case 'type':
        setMediaType(opt.value === '' || opt.value === 'all' ? null : (opt.value as MediaType))
        break
    }
  }

  // 卡片渲染（各组网格共用；调用形式与平铺版一致）
  const renderCard = (a: AssetSummary) => (
    <MediaCard
      key={a.id}
      {...assetToCard(a)}
      onClick={() => openDetail(a.id)}
    />
  )

  return (
    <div className="page" id="page-albums">
      <section className="filter-card">
        {/* 维度行：分区 / 作者 / 角色 / 类型（徽标=各维排自身候选数）+ 最右侧筛选开合 */}
        <div className="pill-row" role="group" aria-label="筛选维度">
          {(Object.keys(DIM_LABELS) as DimKey[]).map((d, i) => (
            <span key={d} className="pill-row-item">
              {i > 0 ? <span className="pill-divider" aria-hidden="true" /> : null}
              <Pill active={dim === d} onClick={() => toggleDim(d)}>
                {DIM_LABELS[d]} <span className="pill-count">{badgeCount(d)}</span>
              </Pill>
            </span>
          ))}
          <button
            type="button"
            className={`more-filter${panelOpen ? ' open' : ''}${hasFilter ? ' active' : ''}`}
            aria-expanded={panelOpen}
            onClick={() => setPanelOpen((v) => !v)}
          >
            更多筛选
            <ChevronDownIcon />
          </button>
        </div>
        {/* 值行：当前维度值胶囊（超阈值默认收起两行）；无筛选且未点开时整行隐藏 */}
        <div className={`pill-row value-row${expanded ? ' expanded' : ''}${filterOpen || panelOpen || hasFilter ? '' : ' collapsed'}`}>
          {dimValues.map(({ label, count, ...opt }) => (
            <Pill
              key={`${opt.kind ?? ''}:${opt.value}`}
              active={isActive(opt)}
              onClick={() => pickValue(opt)}
            >
              {label}
              {count !== undefined ? (
                <span className="pill-count">{count.toLocaleString(LOCALE_ZH)}</span>
              ) : null}
            </Pill>
          ))}
        </div>
        {dimValues.length > VALUE_COLLAPSE_THRESHOLD ? (
          <button className="expand-btn" type="button" onClick={() => setExpanded((v) => !v)}>
            {expanded ? '收起 ⌃' : '展开 ⌄'}
          </button>
        ) : null}
        {/* 更多筛选面板（形态对齐搜索页 search-filters）：首行=排序三档（2026-09-17 用户拍板），
            其后为分区/类型两行；作者/角色行留在页内胶囊（点维度胶囊弹开）不进面板 */}
        <div className={`search-filters${panelOpen ? '' : ' hidden'}`}>
          <div className="f-row">
            <span className="f-label">排序</span>
            <div className="f-opts">
              {SORTS.map((s, i) => (
                <Pill key={s.label} active={sortIdx === i} onClick={() => setSortIdx(i)}>
                  {s.label}
                </Pill>
              ))}
            </div>
          </div>
          <div className="f-row">
            <span className="f-label">分区</span>
            <div className="f-opts">
              <Pill active={partition === 'all'} onClick={() => setPartition('all')}>全部</Pill>
              <Pill active={partition === 'regular'} onClick={() => setPartition('regular')}>常规</Pill>
              <Pill active={partition === 'cos'} onClick={() => setPartition('cos')}>COS</Pill>
            </div>
          </div>
          <div className="f-row">
            <span className="f-label">类型</span>
            <div className="f-opts">
              <Pill active={mediaType === null} onClick={() => setMediaType(null)}>全部</Pill>
              <Pill active={mediaType === 'image'} onClick={() => setMediaType('image')}>图片</Pill>
              <Pill active={mediaType === 'animated_image'} onClick={() => setMediaType('animated_image')}>动图</Pill>
              <Pill active={mediaType === 'video'} onClick={() => setMediaType('video')}>视频</Pill>
            </div>
          </div>
        </div>
        {/* 排序组已收进「更多筛选」面板首行（2026-09-17 用户拍板） */}
      </section>

      {/* 时间分区组：组头（今天/昨天/周X/yyyy-MM-dd + N 项）+ 组内网格（保持原序） */}
      {groups.map((g) => (
        <section className="album-group" key={g.label || '__no-date__'}>
          {g.label ? (
            <h3 className="album-group-title">
              {g.label}
              <span className="album-group-count">{g.assets.length} 项</span>
            </h3>
          ) : null}
          <div className="media-grid">{g.assets.map(renderCard)}</div>
        </section>
      ))}
      {items.length === 0 && !isFetching ? (
        <p className="grid-empty">该筛选组合下暂无内容，换个胶囊试试。</p>
      ) : null}
      {/* E3 无感加载尾部（三件套见 components/media/InfiniteTail） */}
      <InfiniteTail
        isFetchingNextPage={isFetchingNextPage}
        hasNextPage={hasNextPage}
        itemCount={items.length}
        sentinelRef={sentinelRef}
      />
    </div>
  )
}
