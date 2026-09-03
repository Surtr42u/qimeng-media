import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { LOCALE_ZH } from '@/lib/constants'
import {
  useAssetsInfinite, useAssetFacets, facetToOptions,
  type AssetSort, type MediaType, type Partition,
} from '@/hooks/use-assets'
import { formatDuration, formatShortDate } from '@/lib/format'

/**
 * 相册页（原型 #page-albums 移植，阶段 B 四维聚合已接真实数据）：
 * 四维胶囊筛选（分区/作者/角色/类型，GET /assets/facets 排自身口径）+ 内容网格。
 *   - 分区 = all/regular/cos 三态（DOMAIN_RULES §6 COS 隔离；旧版"出处"不再作
 *     为相册维度，出处分组留在搜索/集合入口）；
 *   - 作者 = 双体系作者（key=authorId 回传 GET /assets）；
 *   - 角色 = 常规分区=匹配引擎角色名，COS 分区=作品名（筛选走 work 参数）；
 *   - 类型 = MediaType 四档（含全部）。
 * 计数口径：每个维的候选计数都"排自身"（忽略该维自身选择）——四个 facets
 * 请求各缺一个自己的参数，任何一维的徽标/值行都是排自身计数。
 * 排序映射（文案=原型）：精选=default / 最新=fileDate desc / 最旧=fileDate asc /
 * 按名称=name asc。
 */

type DimKey = 'partition' | 'author' | 'character' | 'type'

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

/** 分区缺省 = 常规：延续常规浏览流不含 COS 的历史口径（COS 走专属胶囊） */
const DEFAULT_PARTITION: Partition = 'regular'

export default function AlbumsPage() {
  const navigate = useNavigate()

  // 选择状态（四维互不重置；切分区清角色——两分区的角色键不同命名空间：
  // 常规=匹配引擎角色名，COS=作品名）
  const [dim, setDimState] = useState<DimKey>('partition')
  const [partition, setPartition] = useState<Partition>(DEFAULT_PARTITION)
  const [authorId, setAuthorId] = useState<string | null>(null)
  const [character, setCharacter] = useState<string | null>(null)
  const [mediaType, setMediaType] = useState<MediaType | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [sortIdx, setSortIdx] = useState(0)

  const sort = SORTS[sortIdx]
  // 角色选择在当前分区下的协议参数形态（COS=work，其余=character）
  const characterParams =
    character === null
      ? {}
      : partition === 'cos'
        ? { work: character }
        : { character }

  // 内容网格：四维选择 → GET /assets 参数（分区三态开关映射见 hooks 注释）
  const listParams = useMemo(
    () => ({
      ...(partition === 'all' ? { includeCos: true } : partition === 'cos' ? { cosOnly: true } : {}),
      ...(authorId ? { authorId } : {}),
      ...characterParams,
      ...(mediaType ? { mediaType } : {}),
      sort: sort.sort,
      order: sort.order,
      limit: 60,
    }),
    [partition, authorId, character, mediaType, sort],
  )

  const { data: pages, isFetching, fetchNextPage, hasNextPage } = useAssetsInfinite(listParams)
  const items = useMemo(() => pages?.pages.flatMap((p) => p.items ?? []) ?? [], [pages])

  // 四维 facets（各缺自身参数 → 每维都是排自身计数；请求本地聚合，量级可控）
  const facetPartition = useAssetFacets({ ...characterParams, ...(authorId ? { authorId } : {}), ...(mediaType ? { mediaType } : {}) })
  const facetAuthor = useAssetFacets({ ...(partition !== DEFAULT_PARTITION ? { partition } : {}), ...characterParams, ...(mediaType ? { mediaType } : {}) })
  const facetCharacter = useAssetFacets({ ...(partition !== DEFAULT_PARTITION ? { partition } : {}), ...(authorId ? { authorId } : {}), ...(mediaType ? { mediaType } : {}) })
  const facetType = useAssetFacets({ ...(partition !== DEFAULT_PARTITION ? { partition } : {}), ...characterParams, ...(authorId ? { authorId } : {}) })

  // 活动维的值行数据 + 各维徽标（排自身候选数；分区/类型扣除「全部」桶）
  const dimValues: { label: string; count?: number; value: string }[] = useMemo(() => {
    switch (dim) {
      case 'partition':
        return facetToOptions(facetPartition.data?.partitions ?? [])
      case 'author':
        return facetToOptions(facetAuthor.data?.authors ?? [])
      case 'character':
        return facetToOptions(facetCharacter.data?.characters ?? [])
      case 'type':
        return facetToOptions(facetType.data?.types ?? [])
    }
  }, [dim, facetPartition.data, facetAuthor.data, facetCharacter.data, facetType.data])

  const currentValue =
    dim === 'partition' ? partition
      : dim === 'author' ? (authorId ?? '')
        : dim === 'character' ? (character ?? '')
          : (mediaType ?? '')

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

  const pickValue = (v: string): void => {
    switch (dim) {
      case 'partition': {
        setPartition(v as Partition)
        setCharacter(null) // 角色键随分区切换换命名空间，重置
        break
      }
      case 'author':
        setAuthorId(v === '' ? null : v)
        break
      case 'character':
        setCharacter(v === '' ? null : v)
        break
      case 'type':
        setMediaType(v === '' || v === 'all' ? null : (v as MediaType))
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
          {dimValues.map(({ label, count, value }) => (
            <button
              key={value}
              className={`pill ${currentValue === value ? 'active' : ''}`}
              type="button"
              onClick={() => pickValue(value)}
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
