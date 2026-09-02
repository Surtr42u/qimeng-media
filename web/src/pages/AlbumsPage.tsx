import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { MediaCard } from '@/components/media/MediaCard'
import { LOCALE_ZH } from '@/lib/constants'
import {
  useAssetsInfinite, useSources, type AssetSort, type MediaType,
} from '@/hooks/use-assets'
import { formatDuration, formatShortDate } from '@/lib/format'

/**
 * 相册页（原型 #page-albums 移植，阶段 B 已接真实数据）：两段式胶囊筛选 +
 * 内容网格（GET /assets，签名缩略图）。
 * 维度（协议支持的筛选面）：分区=出处分组（GET /sources，null 名兜底"其他"）、
 * 类型=MediaType 三档；原型 mock 的"作品/角色"维度待聚合端点后补（记账）。
 * 排序映射（文案=原型）：精选=default / 最新=fileDate desc / 最旧=fileDate asc /
 * 按名称=name asc。
 */

type DimKey = 'partition' | 'type'

interface TypeOption {
  label: string
  value: Exclude<MediaType, undefined> | '全部'
}

const TYPE_VALUES: TypeOption[] = [
  { label: '全部', value: '全部' },
  { label: '视频', value: 'video' },
  { label: '图片', value: 'image' },
  { label: '动图', value: 'animated_image' },
]

/** 排序档（文案与原型一致）→ 协议 sort/order 参数 */
const SORTS: { label: string; sort: AssetSort; order: 'asc' | 'desc' }[] = [
  { label: '精选', sort: 'default', order: 'desc' },
  { label: '最新', sort: 'fileDate', order: 'desc' },
  { label: '最旧', sort: 'fileDate', order: 'asc' },
  { label: '按名称', sort: 'name', order: 'asc' },
]

/** 值行超过该数量（含「全部」）默认收起两行（原型交互阈值） */
const VALUE_COLLAPSE_THRESHOLD = 9

export default function AlbumsPage() {
  const navigate = useNavigate()
  const { data: sources = [] } = useSources()

  // 状态机（结构照原型 albumState：切维度重置 value/expanded，sort 独立保留）
  const [dim, setDimState] = useState<DimKey>('partition')
  const [sourceValue, setSourceValue] = useState('全部')
  const [typeValue, setTypeValue] = useState('全部')
  const [expanded, setExpanded] = useState(false)
  const [sortIdx, setSortIdx] = useState(0)

  const sort = SORTS[sortIdx]

  const listParams = useMemo(() => {
    if (dim === 'partition') {
      return {
        source: sourceValue === '全部' ? undefined : sourceValue,
        sort: sort.sort,
        order: sort.order,
        limit: 60,
      }
    }
    return {
      mediaType: typeValue === '全部' ? undefined : (typeValue as MediaType),
      sort: sort.sort,
      order: sort.order,
      limit: 60,
    }
  }, [dim, sourceValue, typeValue, sort])

  const { data: pages, isFetching, fetchNextPage, hasNextPage } = useAssetsInfinite(listParams)
  const items = useMemo(() => pages?.pages.flatMap((p) => p.items ?? []) ?? [], [pages])

  // 分区维度值（GET /sources；null 名 = "其他"兜底，DOMAIN_RULES §3 口径）
  const sourceValues: [string, number][] = useMemo(() => {
    const vals = sources.map((s) => [s.name ?? '其他', s.fileCount ?? 0] as [string, number])
    return [['全部', vals.reduce((acc, [, c]) => acc + c, 0)], ...vals]
  }, [sources])

  const currentValue = dim === 'partition' ? sourceValue : typeValue
  // 值行统一结构：label 显示 / value 传参（分区 value=出处名，类型 value=MediaType 枚举）
  const dimValues: { label: string; count?: number; value: string }[] =
    dim === 'partition'
      ? sourceValues.map(([n, c]) => ({ label: n, count: c, value: n }))
      : TYPE_VALUES.map((t) => ({ label: t.label, value: t.value }))

  const setDim = (next: DimKey): void => {
    setDimState(next)
    setExpanded(false)
  }

  const pickValue = (v: string): void => {
    if (dim === 'partition') setSourceValue(v)
    else setTypeValue(v)
  }

  return (
    <div className="page" id="page-albums">
      <section className="filter-card">
        {/* 维度行：分区 / 类型（作品/角色维度待聚合端点，记账 HANDOVER_UI §5） */}
        <div className="pill-row" role="group" aria-label="筛选维度">
          <button
            className={`pill ${dim === 'partition' ? 'active' : ''}`}
            type="button"
            onClick={() => setDim('partition')}
          >
            分区 <span className="pill-count">{Math.max(sourceValues.length - 1, 0)}</span>
          </button>
          <span className="pill-divider" aria-hidden="true" />
          <button
            className={`pill ${dim === 'type' ? 'active' : ''}`}
            type="button"
            onClick={() => setDim('type')}
          >
            类型 <span className="pill-count">{TYPE_VALUES.length - 1}</span>
          </button>
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
