import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { LOCALE_ZH } from '@/lib/constants'
import {
  useAssetsInfinite, useAssetFacets, facetToOptions,
  type FacetOptionKind,
  type AssetSort, type MediaType, type Partition,
} from '@/hooks/use-assets'
import { formatDuration, formatShortDate } from '@/lib/format'

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
const SORTS: { label: string; sort: AssetSort; order: 'asc' | 'desc' }[] = [
  { label: '精选', sort: 'default', order: 'desc' },
  { label: '最新', sort: 'fileDate', order: 'desc' },
  { label: '最旧', sort: 'fileDate', order: 'asc' },
  { label: '按名称', sort: 'name', order: 'asc' },
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
  const [sortIdx, setSortIdx] = useState(0)

  const sort = SORTS[sortIdx]

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
      limit: 60,
    }),
    [partition, authorParams, characterParams, mediaType, sort],
  )

  const { data: pages, isFetching, fetchNextPage, hasNextPage } = useAssetsInfinite(listParams)
  const items = useMemo(() => pages?.pages.flatMap((p) => p.items ?? []) ?? [], [pages])

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

  return (
    <div className="page" id="page-albums">
      <section className="filter-card">
        {/* 维度行：分区 / 作者 / 角色 / 类型（徽标=各维排自身候选数） */}
        <div className="pill-row" role="group" aria-label="筛选维度">
          {(Object.keys(DIM_LABELS) as DimKey[]).map((d, i) => (
            <span key={d} className="pill-row-item">
              {i > 0 ? <span className="pill-divider" aria-hidden="true" /> : null}
              <button
                className={`pill ${dim === d ? 'active' : ''}`}
                type="button"
                onClick={() => setDim(d)}
              >
                {DIM_LABELS[d]} <span className="pill-count">{badgeCount(d)}</span>
              </button>
            </span>
          ))}
        </div>
        {/* 值行：当前维度值胶囊（超阈值默认收起两行） */}
        <div className={`pill-row value-row${expanded ? ' expanded' : ''}`}>
          {dimValues.map(({ label, count, ...opt }) => (
            <button
              key={`${opt.kind ?? ''}:${opt.value}`}
              className={`pill ${isActive(opt) ? 'active' : ''}`}
              type="button"
              onClick={() => pickValue(opt)}
            >
              {label}
              {count !== undefined ? (
                <span className="pill-count">{count.toLocaleString(LOCALE_ZH)}</span>
              ) : null}
            </button>
          ))}
        </div>
        {dimValues.length > VALUE_COLLAPSE_THRESHOLD ? (
          <button className="expand-btn" type="button" onClick={() => setExpanded((v) => !v)}>
            {expanded ? '收起 ⌃' : '展开 ⌄'}
          </button>
        ) : null}
        {/* 排序组（独立于维度筛选，文案=原型） */}
        <div className="pill-row sort-row">
          <span className="sort-label">排序</span>
          {SORTS.map((s, i) => (
            <button
              key={s.label}
              className={`pill ${sortIdx === i ? 'active' : ''}`}
              type="button"
              onClick={() => setSortIdx(i)}
            >
              {s.label}
            </button>
          ))}
        </div>
      </section>

      <div className="media-grid">
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
      </div>
      {items.length === 0 && !isFetching ? (
        <p className="grid-empty">该筛选组合下暂无内容，换个胶囊试试。</p>
      ) : null}
      {hasNextPage ? (
        <p className="grid-empty">
          <button
            className="pill"
            type="button"
            disabled={isFetching}
            onClick={() => fetchNextPage()}
          >
            {isFetching ? '加载中…' : '加载更多'}
          </button>
          <span className="pill-count">共 {items.length} 项</span>
        </p>
      ) : null}
    </div>
  )
}
