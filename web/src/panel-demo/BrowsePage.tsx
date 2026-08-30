/**
 * 内容浏览页（原型）：/panel-demo/data/browse/:kind/:id（kind ∈ content | tag | author）。
 *
 * 筛选/媒体卡/预览三件套已抽到 BrowseFilterPanel.tsx 与 GalleryPage（相册）同源复用；
 * 本页保留筛选状态与级联口径逻辑（级联计数、悬空回落），数据全部来自 mock 层。
 * - 维度可见性（旧版逻辑）：标签/作者是多内容聚合 → 四维可筛；
 *   单个内容本身是最小聚合单元 → 只有类型维度。
 * - 级联口径：每个值的计数基于「其他三维已选过滤后」的剩余集合（mock 层 countDimension）。
 * - 换条目（路由参数变化）通过 key 重挂载 BrowseContent 整体重置筛选状态，
 *   无需 effect 手动重置。
 * 点击值胶囊即时过滤下方媒体网格；卡片点击进入文件预览 Dialog。
 */
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { RiArrowLeftLine } from '@remixicon/react'
import { Badge } from '@/components/tremor/badge'
import { Reveal } from './widgets'
import { BrowseFilterPanel, BrowseMediaCard, BrowsePreviewDialog } from './BrowseFilterPanel'
import {
  aggregateBrowseFacets,
  applyBrowseFilter,
  BROWSE_FILTERS_DEFAULT,
  BROWSE_KIND_META,
  countDimension,
  DIMENSION_META,
  generateDetailFiles,
  isBrowseKind,
  summarizeFiles,
  type BrowseDimension,
  type BrowseFilters,
  type BrowseKind,
  type BrowseSortKey,
  type DetailFile,
} from './mock'

export default function BrowsePage() {
  const { kind, id } = useParams()
  // key 绑定路由参数：换条目即重挂载，全部筛选状态自然回到初始值
  return <BrowseContent key={`${kind}:${id}`} kind={kind} id={id} />
}

function BrowseContent({ kind, id }: { kind: string | undefined; id: string | undefined }) {
  const [filters, setFilters] = useState<BrowseFilters>(BROWSE_FILTERS_DEFAULT)
  // 初值按条目类型定：单内容条目只有类型维度可筛
  const [activeDim, setActiveDim] = useState<BrowseDimension>(
    isBrowseKind(kind) && kind === 'content' ? 'type' : 'partition',
  )
  const [valuesExpanded, setValuesExpanded] = useState(false)
  const [sortKey, setSortKey] = useState<BrowseSortKey>('最新')
  const [preview, setPreview] = useState<DetailFile | null>(null)

  // hooks 需固定数量：无效 kind 时用兜底值计算，渲染层再拦截
  const entryKind: BrowseKind = isBrowseKind(kind) ? kind : 'content'
  const entryName = id ?? '未知条目'
  // 维度可见性（旧版逻辑）：标签/作者是多内容聚合 → 四维可筛；
  // 单个内容本身就是最小聚合单元 → 只有类型维度
  const visibleDims: BrowseDimension[] = useMemo(
    () => (entryKind === 'content' ? ['type'] : DIMENSION_META.map((d) => d.key)),
    [entryKind],
  )

  // seed 加 kind 前缀隔离同名条目（标签/作者/内容可重名），显示名保持纯净
  const { files, summary, facets } = useMemo(() => {
    const list = generateDetailFiles(entryName, 24, undefined, `${entryKind}:${entryName}`)
    return {
      files: list,
      summary: summarizeFiles(list),
      // 概览计数（角色 N · 作品 M）基于条目全量，不受筛选影响
      facets: aggregateBrowseFacets(list),
    }
  }, [entryKind, entryName])

  // 各维度的值计数（mock 层已实现级联口径：计数排除本维度自身的过滤）
  const dimCounts = useMemo(
    () =>
      Object.fromEntries(
        visibleDims.map((dim) => [dim, countDimension(files, dim, filters)]),
      ) as Record<BrowseDimension, ReturnType<typeof countDimension>>,
    [files, filters, visibleDims],
  )

  // 维度行的维度值数量（「全部」不计）
  const dimensionValueCounts = useMemo(() => {
    return Object.fromEntries(
      visibleDims.map((dim) => [dim, (dimCounts[dim]?.length ?? 1) - 1]),
    ) as Record<BrowseDimension, number>
  }, [visibleDims, dimCounts])

  // 胶囊过滤 + 排序（mock 层纯函数），useMemo 避免每次渲染重算
  const shown = useMemo(() => applyBrowseFilter(files, filters, sortKey), [files, filters, sortKey])

  // 级联收缩的悬空回落：某维度的选中值在其他维度变化后计数归零（从值列表消失）时，
  // 自动重置为「全部」——避免停留在 0 结果的死状态（countDimension 已排除本维度自身）。
  // 这是刻意的状态联动（响应级联收缩的旧选中值），无法在渲染期无副作用地派生
  useEffect(() => {
    for (const { key } of DIMENSION_META) {
      if (filters[key] === '全部') continue
      const counts = countDimension(files, key, filters)
      if (!counts.some((c) => c.value === filters[key])) {
        // oxlint-disable-next-line react/set-state-in-effect -- 悬空回落必须滞后一拍重置 state，派生写法会让 countDimension 的级联口径与选中值 state 失步
        setFilters((prev) => ({ ...prev, [key]: '全部' }))
        break
      }
    }
  }, [files, filters])

  if (!isBrowseKind(kind)) {
    return (
      <div className="space-y-4">
        <BackLink label="返回我的" to="/panel-demo/mine" />
        <p className="text-sm text-muted-foreground">未知条目类型（kind={kind}），请从榜单进入。</p>
      </div>
    )
  }

  const meta = BROWSE_KIND_META[entryKind]
  const activeCounts = dimCounts[activeDim] ?? []

  return (
    <div className="space-y-6">
      {/* 条目头：返回链接 + 名称 + 类型 Badge + 概览小字段 */}
      <Reveal index={0}>
        <div>
          <BackLink label={`返回${meta.backLabel}`} to={`/panel-demo/data/rank/${meta.rankType}`} />
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <h2 className="text-lg font-semibold">{entryName}</h2>
            <Badge variant="default">{meta.badge}</Badge>
          </div>
          <p className="mt-0.5 text-xs text-muted-foreground">
            总文件数 {summary.fileCount} · 总大小 {summary.totalSize} · 总浏览{' '}
            {summary.totalViews.toLocaleString('zh-CN')}
            {facets.roles.length > 0 ? ` · 角色 ${facets.roles.length}` : ''}
            {facets.works.length > 0 ? ` · 作品 ${facets.works.length}` : ''} · mock 数据
          </p>
        </div>
      </Reveal>

      {/* 两段式胶囊筛选：维度行 + 值行 + 排序组（与相册页同源组件） */}
      <Reveal index={1}>
        <BrowseFilterPanel
          visibleDims={visibleDims}
          dimensionValueCounts={dimensionValueCounts}
          activeDim={activeDim}
          onActiveDimChange={(dim) => {
            setActiveDim(dim)
            setValuesExpanded(false)
          }}
          activeCounts={activeCounts}
          filters={filters}
          onFilterChange={(dim, value) => setFilters((prev) => ({ ...prev, [dim]: value }))}
          valuesExpanded={valuesExpanded}
          onValuesExpandedChange={setValuesExpanded}
          sortKey={sortKey}
          onSortChange={setSortKey}
        />
      </Reveal>

      {/* 内容网格：2-5 列随宽度自适应 */}
      <Reveal index={2}>
        {shown.length > 0 ? (
          <section
            aria-label="内容网格"
            className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5"
          >
            {shown.map((file, index) => (
              // key 用 index+name：同 seed 随机后缀存在小概率重名
              <BrowseMediaCard
                key={`${index}-${file.name}`}
                file={file}
                onOpen={() => setPreview(file)}
              />
            ))}
          </section>
        ) : (
          <p className="py-12 text-center text-sm text-muted-foreground">
            该筛选组合下暂无内容，换个胶囊试试。
          </p>
        )}
      </Reveal>

      {/* 文件预览 Dialog：点击时挂载、关闭时卸载 */}
      {preview ? <BrowsePreviewDialog file={preview} onClose={() => setPreview(null)} /> : null}
    </div>
  )
}

/** 返回链接（榜单详情页 / 兜底提示页共用样式） */
function BackLink({ label, to }: { label: string; to: string }) {
  return (
    <Link
      to={to}
      className="inline-flex items-center gap-1 text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
      <RiArrowLeftLine className="size-4" aria-hidden={true} />
      {label}
    </Link>
  )
}
