/**
 * 相册页（正式 UI）：全量 mock 内容池的整理视角。
 *
 * 与 BrowsePage（榜单条目浏览）复用同一套筛选三件套（BrowseFilterPanel.tsx）：
 * - 条目集合 = generateGalleryItems() 全量池（24 个榜单条目 × 6 文件 = 144 文件），
 *   四维 facet（分区/作品/角色/类型）在全量上聚合；
 * - 筛选交互与 BrowsePage 完全一致（两段式胶囊 + 级联计数 + 悬空回落 + 排序组）；
 * - 相册是整理视角：单击开预览 Dialog 即可，无双击播放/右侧详情面板。
 */
import { useEffect, useMemo, useState } from 'react'
import { Reveal } from './widgets'
import { BrowseFilterPanel, BrowseMediaCard, BrowsePreviewDialog } from './BrowseFilterPanel'
import {
  applyBrowseFilter,
  BROWSE_FILTERS_DEFAULT,
  countDimension,
  DIMENSION_META,
  generateGalleryItems,
  summarizeFiles,
  type BrowseDimension,
  type BrowseFilters,
  type BrowseSortKey,
  type DetailFile,
} from './mock'

/** 页头文案 */
const GALLERY_TEXT = { title: '相册', note: '全量内容池 · mock 数据' } as const

export default function GalleryPage() {
  const [filters, setFilters] = useState<BrowseFilters>(BROWSE_FILTERS_DEFAULT)
  const [activeDim, setActiveDim] = useState<BrowseDimension>('partition')
  const [valuesExpanded, setValuesExpanded] = useState(false)
  const [sortKey, setSortKey] = useState<BrowseSortKey>('最新')
  const [preview, setPreview] = useState<DetailFile | null>(null)

  // 全量池挂载生成一次；页头汇总基于全量
  const files = useMemo(() => generateGalleryItems(), [])
  const summary = useMemo(() => summarizeFiles(files), [files])
  // 相册是全量池：四维全部可筛
  const visibleDims = useMemo(() => DIMENSION_META.map((d) => d.key), [])

  // 各维度的值计数（级联口径：计数排除本维度自身的过滤，与 BrowsePage 一致）
  const dimCounts = useMemo(
    () =>
      Object.fromEntries(
        visibleDims.map((dim) => [dim, countDimension(files, dim, filters)]),
      ) as Record<BrowseDimension, ReturnType<typeof countDimension>>,
    [files, filters, visibleDims],
  )

  // 维度行的维度值数量（「全部」不计）
  const dimensionValueCounts = useMemo(
    () =>
      Object.fromEntries(
        visibleDims.map((dim) => [dim, (dimCounts[dim]?.length ?? 1) - 1]),
      ) as Record<BrowseDimension, number>,
    [visibleDims, dimCounts],
  )

  // 胶囊过滤 + 排序（mock 层纯函数）
  const shown = useMemo(() => applyBrowseFilter(files, filters, sortKey), [files, filters, sortKey])

  // 级联收缩的悬空回落（与 BrowsePage 同一口径）：选中值从值列表消失时重置为「全部」。
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

  return (
    <div className="space-y-6">
      {/* 页头：标题 + 总文件数 / 总大小 */}
      <Reveal index={0}>
        <div>
          <h2 className="text-lg font-semibold">{GALLERY_TEXT.title}</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">
            总文件数 {summary.fileCount.toLocaleString('zh-CN')} · 总大小 {summary.totalSize} ·{' '}
            {GALLERY_TEXT.note}
          </p>
        </div>
      </Reveal>

      {/* 两段式胶囊筛选（与 BrowsePage 同源组件） */}
      <Reveal index={1}>
        <BrowseFilterPanel
          visibleDims={visibleDims}
          dimensionValueCounts={dimensionValueCounts}
          activeDim={activeDim}
          onActiveDimChange={(dim) => {
            setActiveDim(dim)
            setValuesExpanded(false)
          }}
          activeCounts={dimCounts[activeDim] ?? []}
          filters={filters}
          onFilterChange={(dim, value) => setFilters((prev) => ({ ...prev, [dim]: value }))}
          valuesExpanded={valuesExpanded}
          onValuesExpandedChange={setValuesExpanded}
          sortKey={sortKey}
          onSortChange={setSortKey}
        />
      </Reveal>

      {/* 内容网格：与 BrowsePage 相同的媒体卡 */}
      <Reveal index={2}>
        {shown.length > 0 ? (
          <section
            aria-label="内容网格"
            className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5"
          >
            {shown.map((file, index) => (
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

      {/* 预览 Dialog：相册为整理视角，单击即预览 */}
      {preview ? <BrowsePreviewDialog file={preview} onClose={() => setPreview(null)} /> : null}
    </div>
  )
}
