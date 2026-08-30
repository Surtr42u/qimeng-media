/**
 * 业务统计内容组件（原型）：全部为 mock 业务数据。
 *
 * V4 结构调整：本文件不再挂路由（/data 重定向到 /mine），改为导出 DataContent
 * 供 MinePage 的「数据」Tab 引用——数据总览成为「我的」页的一个标签页。
 *
 * V3 反馈落地：排行榜 Tabs 大卡拆成三个独立小卡（三列并排、窄屏堆叠），
 * 每卡只展示前 5 条紧凑行；行点击直接进入内容浏览页，卡头「查看全部」
 * 进入对应榜单详情页（明细 Dialog / 预览一并移到浏览页与榜单页）。
 * 其余区块（时间筛选条 / 6 指标卡 / 趋势线 / 双环）保持 V2 不动。
 */
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  RiArrowRightSLine,
  RiFireLine,
  RiPriceTag3Line,
  RiUser3Line,
} from '@remixicon/react'
import { Card } from '@/components/tremor/card'
import { DonutChart } from '@/components/tremor/donut-chart'
import { LineChart } from '@/components/tremor/line-chart'
import { MetricCardView, RankBadge, Reveal, SegmentedControl } from './widgets'
import {
  BROWSE_KIND_BY_RANK,
  CONTENT_DONUT,
  DATA_BY_RANGE,
  MOCK_CONTENT_BY_COUNT,
  RANK_KINDS,
  RANK_PAGE_TITLES,
  TIME_RANGE_TABS,
  TREND_SERIES,
  entryMainValue,
  mapTrendPoints,
  type DataRangeData,
  type DonutSlice,
  type RankBoard,
  type RankKind,
  type TimeRange,
} from './mock'
import { getColorClassName, type AvailableChartColorsKeys } from '@/lib/tremor'

/** 大数紧凑格式（双环中心用，避免 6 位数撑爆 w-40 圆心） */
const fmtCompact = (v: number) =>
  v >= 10_000 ? `${(v / 10_000).toFixed(1)} 万` : v.toLocaleString('zh-CN')

/** 内容分布双环卡（左右两卡同构：环 + 中心文案 + 自绘图例） */
function DonutCard({
  title,
  sub,
  data,
  centerLabel,
}: {
  title: string
  sub: string
  data: DonutSlice[]
  centerLabel: string
}) {
  const total = data.reduce((sum, d) => sum + d.value, 0)
  return (
    <Card>
      <h2 className="text-sm font-semibold">{title}</h2>
      <p className="mt-0.5 mb-3 text-xs text-muted-foreground">{sub}</p>
      <div className="flex flex-col items-center gap-4 sm:flex-row sm:gap-6">
        {/* 外层 relative 定位中心总数；覆盖层 pointer-events-none 不挡扇区交互 */}
        <div className="relative h-40 w-40 shrink-0">
          <DonutChart
            data={data.map((d) => ({ name: d.name, value: d.value }))}
            category={CONTENT_DONUT.category}
            value="value"
            colors={[...CONTENT_DONUT.colors]}
            valueFormatter={(value) => value.toLocaleString('zh-CN')}
          />
          <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
            <p className="text-xl font-semibold tabular-nums">{fmtCompact(total)}</p>
            <p className="text-xs text-muted-foreground">{centerLabel}</p>
          </div>
        </div>
        {/* 自绘图例（DonutChart 无内置图例）：色点 + 名称 + 数量 + 占比 */}
        <ul className="flex flex-col gap-2">
          {data.map((d, index) => (
            <li key={d.name} className="flex items-center gap-2 text-sm">
              <span
                className={`size-2.5 shrink-0 rounded-sm ${getColorClassName(
                  CONTENT_DONUT.colors[index] as AvailableChartColorsKeys,
                  'bg',
                )}`}
                aria-hidden={true}
              />
              <span className="text-muted-foreground">{d.name}</span>
              <span className="font-medium tabular-nums">{d.value.toLocaleString('zh-CN')}</span>
              <span className="text-xs tabular-nums text-muted-foreground">
                {((d.value / total) * 100).toFixed(1)}%
              </span>
            </li>
          ))}
        </ul>
      </div>
    </Card>
  )
}

/** 排行小卡图标（UI 资源不放 mock 层，此处按 kind 映射；aria-hidden 用 Booleanish 对齐 remixicon 类型） */
const RANK_ICONS: Record<
  RankKind,
  React.ComponentType<{ className?: string; 'aria-hidden'?: boolean | 'true' | 'false' }>
> = {
  content: RiFireLine,
  tags: RiPriceTag3Line,
  authors: RiUser3Line,
}

/** 总览页排行小卡：卡头（图标 + 标题 + 查看全部）+ 前 5 条紧凑行，行点击进内容浏览页 */
function RankCard({ kind, board }: { kind: RankKind; board: RankBoard }) {
  const navigate = useNavigate()
  const Icon = RANK_ICONS[kind]
  return (
    <Card className="flex flex-col">
      <div className="flex items-center gap-2">
        <Icon className="size-4 shrink-0 text-primary" aria-hidden={true} />
        <h2 className="text-sm font-semibold">{RANK_PAGE_TITLES[kind]}</h2>
        <Link
          to={`/panel-demo/data/rank/${kind}`}
          className="ml-auto inline-flex items-center gap-0.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          查看全部
          <RiArrowRightSLine className="size-3.5" aria-hidden={true} />
        </Link>
      </div>
      <p className="mt-0.5 mb-1 text-xs text-muted-foreground">Top 5 · 点击条目浏览全部内容</p>
      <ul className="-mx-1.5 flex-1 divide-y divide-border">
        {board.items.slice(0, 5).map((entry, rank) => (
          <li key={entry.name}>
            <button
              type="button"
              onClick={() =>
                navigate(
                  `/panel-demo/data/browse/${BROWSE_KIND_BY_RANK[kind]}/${encodeURIComponent(entry.name)}`,
                )
              }
              className="flex w-full items-center gap-2.5 rounded-md px-1.5 py-2 text-left transition-colors hover:bg-accent/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
            >
              <RankBadge rank={rank} />
              <span className="min-w-0 flex-1 truncate text-sm">{entry.name}</span>
              {/* 主指标值随榜单语义（浏览量 / 关联文件数 / 作品数） */}
              <span className="shrink-0 text-sm font-semibold tabular-nums">
                {entryMainValue(entry, kind).toLocaleString('zh-CN')}
              </span>
              <span className="w-16 shrink-0 text-right text-[11px] text-muted-foreground">
                {board.metricLabel}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </Card>
  )
}

/** 业务统计内容（MinePage「数据」Tab 引用；状态自包含，含时间范围切换） */
export function DataContent() {
  const [range, setRange] = useState<TimeRange>('30d')

  const data: DataRangeData = DATA_BY_RANGE[range]

  return (
    <div className="space-y-6">
      {/* 时间筛选条：分段按钮组（选中主色），右侧当前范围文案 */}
      <Reveal index={0}>
        <section
          aria-label="统计时间范围"
          className="flex flex-wrap items-center justify-between gap-3"
        >
          <SegmentedControl
            options={TIME_RANGE_TABS}
            value={range}
            onChange={setRange}
            ariaLabel="统计时间范围"
          />
          <p className="text-xs text-muted-foreground">{data.rangeText}</p>
        </section>
      </Reveal>

      {/* 业务指标卡行（6 张，含互动数据：点赞 / 收藏） */}
      <Reveal index={1}>
        <section
          aria-label="业务指标"
          className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
        >
          {data.metrics.map((metric) => (
            <MetricCardView key={metric.label} {...metric} />
          ))}
        </section>
      </Reveal>

      {/* 浏览与播放趋势（随时间范围切换） */}
      <Reveal index={2}>
        <Card>
          <div className="mb-4">
            <h2 className="text-sm font-semibold">浏览与播放趋势</h2>
            <p className="mt-0.5 text-xs text-muted-foreground">{data.rangeText} · mock 数据</p>
          </div>
          <LineChart
            data={mapTrendPoints(data.trend)}
            index={TREND_SERIES.index}
            categories={[...TREND_SERIES.categories]}
            colors={[...TREND_SERIES.colors]}
            yAxisWidth={48}
            valueFormatter={(value) => value.toLocaleString('zh-CN')}
            className="h-72"
          />
        </Card>
      </Reveal>

      {/* 内容分布双环：左=资产数量占比（存量，不随范围变），右=浏览量占比（随范围变） */}
      <Reveal index={3}>
        <section className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <DonutCard
            title="内容类型分布"
            sub="按资产数量占比"
            data={MOCK_CONTENT_BY_COUNT}
            centerLabel="总内容"
          />
          <DonutCard
            title="浏览量占比"
            sub={`按类型的浏览次数占比 · ${data.rangeText}`}
            data={data.contentByViews}
            centerLabel="总浏览"
          />
        </section>
      </Reveal>

      {/* 排行榜：三个独立小卡并排（窄屏堆叠），V3 反馈替代原 Tabs 大卡 */}
      <Reveal index={4}>
        <section aria-label="排行榜" className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          {RANK_KINDS.map((kind) => (
            <RankCard key={kind} kind={kind} board={data.boards[kind]} />
          ))}
        </section>
      </Reveal>
    </div>
  )
}
