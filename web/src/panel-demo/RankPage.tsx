/**
 * 榜单详情页（原型）：/panel-demo/data/rank/:type（content | tags | authors）。
 *
 * V3 反馈落地：统计概览卡 + 排序切换（按浏览/收藏/时长，重排列表）
 * + 默认 Top 20、「显示更多」展开 Top 50（再点收起）；
 * 行点击进入内容浏览页——V2 的明细 Dialog 在此页废弃。
 *
 * 数据口径：取「全部」时间范围的全量榜单（mock 50 条），与总览页时间筛选解耦。
 */
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { RiArrowLeftLine } from '@remixicon/react'
import { Card } from '@/components/tremor/card'
import { Badge } from '@/components/tremor/badge'
import { Button } from '@/components/tremor/button'
import { RankBadge, Reveal, SegmentedControl } from './widgets'
import { useCountUp } from './use-count-up'
import {
  BROWSE_KIND_BY_RANK,
  DATA_BY_RANGE,
  RANK_OVERVIEWS,
  RANK_PAGE_TITLES,
  RANK_SORT_TABS,
  entrySortValue,
  fmtSortValue,
  isRankKind,
  type RankEntry,
  type RankKind,
  type RankOverviewStat,
  type RankSortKey,
} from './mock'

/** 默认展示条数 / 全量条数（任务口径：默认 Top 20，展开 Top 50） */
const DEFAULT_COUNT = 20
const FULL_COUNT = 50

/** 排序维度 → 行内次级指标名（与 RANK_SORT_TABS 的「按 x」对应） */
const SORT_VALUE_LABELS: Record<RankSortKey, string> = {
  views: '浏览',
  saves: '收藏',
  duration: '时长',
}

/** 概览统计卡：value 数字滚动（useCountUp），text 文本值（如最热标签）直显 */
function OverviewStatCard({ stat }: { stat: RankOverviewStat }) {
  const animated = useCountUp(stat.value ?? 0)
  return (
    <Card className="py-5 transition-shadow duration-200 ring-1 ring-transparent hover:shadow-lg hover:shadow-[var(--qm-primary-soft)] hover:ring-[var(--qm-primary-soft)]">
      <p className="text-sm text-muted-foreground">{stat.label}</p>
      <p className="mt-2 truncate text-2xl font-semibold tabular-nums tracking-tight">
        {stat.value !== undefined ? (
          animated.toLocaleString('zh-CN')
        ) : (
          <span className="tabular-nums">{stat.text}</span>
        )}
        {stat.suffix ? (
          <span className="ml-1 text-sm font-medium text-muted-foreground">{stat.suffix}</span>
        ) : null}
      </p>
      {stat.sub ? <p className="mt-1.5 text-xs text-muted-foreground">{stat.sub}</p> : null}
    </Card>
  )
}

/** 榜单行（沿用 V2 行样式：排名徽标 / 名称 / 占比小条 / 指标值 / 涨跌），整行点击进内容浏览页 */
function RankRow({
  entry,
  rank,
  sortKey,
  max,
  browseKind,
}: {
  entry: RankEntry
  rank: number
  sortKey: RankSortKey
  max: number
  browseKind: RankKind
}) {
  const navigate = useNavigate()
  const value = entrySortValue(entry, sortKey)
  return (
    <li>
      <button
        type="button"
        onClick={() =>
          navigate(`/panel-demo/data/browse/${BROWSE_KIND_BY_RANK[browseKind]}/${encodeURIComponent(entry.name)}`)
        }
        className="flex w-full items-center gap-3 rounded-md px-2 py-2.5 text-left transition-colors hover:bg-accent/50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
      >
        <RankBadge rank={rank} />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">{entry.name}</span>
          {/* 迷你占比条：相对榜首的比例，宽度随排序维度切换联动 */}
          <span className="mt-1.5 block h-1 w-full overflow-hidden rounded-full bg-muted" aria-hidden={true}>
            <span
              className="block h-full rounded-full bg-primary/70"
              style={{ width: `${Math.max((value / max) * 100, 4)}%` }}
            />
          </span>
        </span>
        <span className="w-24 shrink-0 text-right">
          <span className="block text-sm font-semibold tabular-nums">{fmtSortValue(value, sortKey)}</span>
          <span className="block text-[11px] text-muted-foreground">{SORT_VALUE_LABELS[sortKey]}</span>
        </span>
        <Badge variant={entry.deltaType === 'up' ? 'success' : 'error'} className="shrink-0">
          {entry.delta}
        </Badge>
      </button>
    </li>
  )
}

export default function RankPage() {
  const { type } = useParams()
  const [sortKey, setSortKey] = useState<RankSortKey>('views')
  const [expanded, setExpanded] = useState(false)

  // hooks 需固定数量：无效 type 时用兜底 kind 计算，渲染层再拦截
  const kind: RankKind = isRankKind(type) ? type : 'content'
  const board = DATA_BY_RANGE.all.boards[kind]

  // 排序切换 = 本地重排全量列表（mock 数据已含四维数值，纯前端排序）
  const sorted = useMemo(() => {
    const list = [...board.items]
    list.sort((a, b) => entrySortValue(b, sortKey) - entrySortValue(a, sortKey))
    return list
  }, [board, sortKey])

  if (!isRankKind(type)) {
    return (
      <div className="space-y-4">
        <BackLink />
        <p className="text-sm text-muted-foreground">未知榜单类型（type={type}），请从「我的」页数据标签进入。</p>
      </div>
    )
  }

  const visible = expanded ? FULL_COUNT : DEFAULT_COUNT
  const shown = sorted.slice(0, visible)
  // 占比条基准 = 当前排序维度的榜首值（sorted 已降序，取第一个）
  const max = sorted.length > 0 ? entrySortValue(sorted[0], sortKey) : 1

  return (
    <div className="space-y-6">
      {/* 顶部：返回链接 + 页标题 */}
      <Reveal index={0}>
        <div>
          <BackLink />
          <h2 className="mt-1 text-lg font-semibold">{RANK_PAGE_TITLES[kind]}</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">
            全部时间口径 · mock 数据 · 点击条目进入内容浏览
          </p>
        </div>
      </Reveal>

      {/* 统计概览卡（按榜单类型不同：内容 / 标签 / 作者） */}
      <Reveal index={1}>
        <section
          aria-label="榜单概览"
          className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
        >
          {RANK_OVERVIEWS[kind].map((stat) => (
            <OverviewStatCard key={stat.label} stat={stat} />
          ))}
        </section>
      </Reveal>

      {/* 列表卡：工具栏（左侧条数说明 + 右侧排序切换）+ Top20/50 + 展开收起 */}
      <Reveal index={2}>
        <Card>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-xs text-muted-foreground">
              共 {sorted.length} 条 · 当前显示 {shown.length} 条
            </p>
            <SegmentedControl
              options={RANK_SORT_TABS}
              value={sortKey}
              onChange={setSortKey}
              ariaLabel="排序方式"
            />
          </div>
          <ul className="mt-2 divide-y divide-border">
            {shown.map((entry, rank) => (
              <RankRow
                key={entry.name}
                entry={entry}
                rank={rank}
                sortKey={sortKey}
                max={max}
                browseKind={kind}
              />
            ))}
          </ul>
          <div className="mt-4 flex justify-center">
            <Button variant="secondary" onClick={() => setExpanded(!expanded)}>
              {expanded ? '收起' : `显示更多（Top ${FULL_COUNT}）`}
            </Button>
          </div>
        </Card>
      </Reveal>
    </div>
  )
}

/** 返回「我的」链接（榜单页由我的页数据 Tab 的排行卡进入，数据总览已并入该页） */
function BackLink() {
  return (
    <Link
      to="/panel-demo/mine"
      className="inline-flex items-center gap-1 text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
      <RiArrowLeftLine className="size-4" aria-hidden={true} />
      返回我的
    </Link>
  )
}
