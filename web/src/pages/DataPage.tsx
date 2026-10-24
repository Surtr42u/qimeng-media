import { useMemo, useState, type MouseEvent } from 'react'
import { useNavigate } from 'react-router'
import type { StatsOverview } from '@/api/generated'
import { ContentRankGrid } from '@/components/data/ContentRankGrid'
import { DonutCard, type DonutSlice } from '@/components/data/DonutCard'
import { RankRowList } from '@/components/data/RankRowList'
import { TrendChart } from '@/components/data/TrendChart'
import { LoadingHint } from '@/components/ui/loading-hint'
import { useAuthors } from '@/hooks/use-authors'
import { useRankings, useStatsOverview, useTrends, useStatsTopAuthors, useStatsTopTags } from '@/hooks/use-stats'
import { LOCALE_ZH } from '@/lib/constants'
import { authorDisplayName, formatBytes, formatDurationSeconds } from '@/lib/format'
import { STATS_SEGMENTS, STATS_PROTOCOL_MESSAGE, STATS_LOAD_FAILED_MESSAGE } from '@/lib/stats-contract'
import { authorRankRows, tagRankRows } from '@/lib/rank-rows'
import { assetDetail, COLLECTION_AUTHOR, COLLECTION_TAG, RANK_AUTHORS, RANK_CONTENT, RANK_TAGS, collectionPath } from '@/lib/route-keys'

/**
 * 数据页（原型 #page-data 移植，阶段 B 已接真实数据）：时段胶囊 + 6 指标卡 +
 * 趋势折线 + 双环分布 + 排行榜（Top5/标签/作者/作者总览）。
 * 时段四档（近 7/30/90 天/全部）联动窗口数字、趋势、浏览占比及统计榜，库存不随窗口变化；
 * 指标/双环来自 GET /stats/overview 与 /stats/trends（DOMAIN_RULES §5 口径）。
 */

const SEGMENTS = STATS_SEGMENTS

/** 默认档（原型打开即「7 天」，此处映射近 7 天） */
const DEFAULT_SEG = 0

/** 内容榜/标签榜/作者榜展示条数（Top 5） */
const RANK_TOP_COUNT = 5

/** 6 指标卡（label 固定；值全部来自 GET /stats/overview；无涨跌数据源，delta 不渲染） */
const METRIC_DEFS: { label: string; pick: (s: StatsOverview) => string }[] = [
  { label: '总浏览次数', pick: (s) => (s.windowTotals?.viewCount ?? 0).toLocaleString(LOCALE_ZH) },
  { label: '总播放次数', pick: (s) => (s.windowTotals?.playCount ?? 0).toLocaleString(LOCALE_ZH) },
  { label: '总浏览时长', pick: (s) => formatDurationSeconds(s.windowTotals?.seconds ?? 0) },
  { label: '总文件', pick: (s) => (s.totalFiles ?? 0).toLocaleString(LOCALE_ZH) },
  { label: '总容量', pick: (s) => formatBytes(s.totalSizeBytes ?? 0) },
  { label: '平均浏览次数', pick: (s) => s.avgViewsPerFile == null ? '—' : s.avgViewsPerFile.toFixed(1) },
]

/** 「查看全部」跳完整榜单子页（保留原型 a 标签与 data-rank，拦截默认锚点跳转） */
function rankMoreProps(navigate: ReturnType<typeof useNavigate>, rank: string, range: string) {
  return {
    href: '#',
    className: 'rank-more',
    'data-rank': rank,
    onClick: (e: MouseEvent<HTMLAnchorElement>) => {
      e.preventDefault()
      navigate(`/app/ranks/${rank}?range=${encodeURIComponent(range)}`)
    },
  }
}

export default function DataPage() {
  const navigate = useNavigate()
  const [seg, setSeg] = useState(DEFAULT_SEG)

  const current = SEGMENTS[seg]

  const overviewQuery = useStatsOverview(current.trends)
  const { data: overview, isPending: overviewPending } = overviewQuery
  const trendQuery = useTrends(current.trends)
  const contentRank = useRankings(current.period, RANK_TOP_COUNT)
  const tagQuery = useStatsTopTags(current.trends, RANK_TOP_COUNT)
  const tags = tagQuery.data ?? []
  const authorQuery = useStatsTopAuthors(current.trends, RANK_TOP_COUNT)
  const statsAuthors = authorQuery.data ?? []
  const authorsQuery = useAuthors()
  const authors = authorsQuery.data ?? []

  // 分类和值均由服务端给出；这里仅换算环形图弧长与展示字符串。
  const typeSlices = useMemo<DonutSlice[]>(() => {
    if (!overview?.totalFiles) return []
    return [
      { name: '图片', count: overview.stillImageCount, dot: 'd2', arc: 'arc-2' },
      { name: '视频', count: overview.videoCount ?? 0, dot: 'd1', arc: 'arc-1' },
      { name: '动图', count: overview.animatedImageCount, dot: 'd4', arc: 'arc-4' },
    ].map(({ name, count, dot, arc }) => ({ name, dot, arc,
      value: count.toLocaleString(LOCALE_ZH), pct: count / overview.totalFiles! * 100 }))
  }, [overview])
  const viewSlices = useMemo<DonutSlice[]>(() => {
    const total = overview?.windowTotals.viewCount ?? 0
    if (!overview || !total) return []
    return [
      { key: 'image', name: '图片', dot: 'd2', arc: 'arc-2' },
      { key: 'video', name: '视频', dot: 'd1', arc: 'arc-1' },
      { key: 'animated_image', name: '动图', dot: 'd4', arc: 'arc-4' },
    ].map(({ key, name, dot, arc }) => {
      const count = overview.mediaTypeWindowTotals[key].viewCount
      return { name, dot, arc, value: count.toLocaleString(LOCALE_ZH), pct: count / total * 100 }
    })
  }, [overview])
  const tagRows = useMemo(() => tagRankRows(tags), [tags])
  const authorRows = useMemo(() => authorRankRows(statsAuthors), [statsAuthors])
  // 作者总览卡（原型 renderAuthorOverview）：作者管理入口的预览卡——行数与相邻
  // 榜单卡一致取 Top5（2026-09-04 用户反馈：真库 122 位作者全量渲染致栏目过长；
  // 总量看头注「N 位作者 · 已关注 M」，完整列表走「管理」进作者管理页），
  // 按文件数降序（作者管理页「文件数量」档同口径），副标题「N 个文件」
  // （浏览次数不展示——2026-09-05 反馈⑤，旧版无此元素）。
  const authorOverviewRows = useMemo(
    () =>
      authors
        .slice()
        .sort((a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0))
        .slice(0, RANK_TOP_COUNT)
        .map((a) => ({
          name: authorDisplayName(a),
          count: '',
          sub: `${a.fileCount ?? 0} 个文件`,
        })),
    [authors],
  )
  const followedCount = useMemo(() => authors.filter((a) => a.followed).length, [authors])

  // 趋势全 0（含空桶）→ 暂无趋势数据（DOMAIN_RULES §5 空数据口径）
  const trendAllZero =
    !trendQuery.data ||
    trendQuery.data.every((b) => (b.viewCount ?? 0) === 0 && (b.playCount ?? 0) === 0)

  const queries = [overviewQuery, trendQuery, contentRank, tagQuery, authorQuery, authorsQuery]
  const failed = queries.find((query) => query.isError)
  if (failed) return <div className="page" id="page-data"><div className="chart-card" role="alert">
    <p>{failed.error?.message === STATS_PROTOCOL_MESSAGE ? STATS_PROTOCOL_MESSAGE : STATS_LOAD_FAILED_MESSAGE}</p>
    <button type="button" className="pill" onClick={() => void Promise.all(queries.map((query) => query.refetch()))}>重试</button>
  </div></div>

  return (
    <div className="page" id="page-data">
      <div className="seg-row">
        {SEGMENTS.map((s, i) => (
          <button
            key={s.label}
            className={seg === i ? 'seg active' : 'seg'}
            type="button"
            onClick={() => setSeg(i)}
          >
            {s.label}
          </button>
        ))}
        <span className="seg-note">{current.note}</span>
      </div>
      <div className="metric-grid">
        {METRIC_DEFS.map((m) => {
          const value = overview ? m.pick(overview) : '—'
          return (
            <div className="metric-card" key={m.label}>
              <p className="m-label">{m.label}</p>
              <p className="m-value">{value}</p>
            </div>
          )
        })}
      </div>
      <div className="chart-card">
        <h3>浏览与播放趋势</h3>
        <p>{current.note} · 按浏览/播放数</p>
        {trendQuery.isPending ? (
          <LoadingHint className="m-note" />
        ) : trendAllZero ? (
          <p className="m-note">暂无趋势数据</p>
        ) : (
          <TrendChart buckets={trendQuery.data ?? []} />
        )}
      </div>
      <div className="donut-grid">
        <DonutCard title="内容类型分布" sub="按资产数量占比" slices={typeSlices} loading={overviewPending} />
        <DonutCard
          title="浏览量占比"
          sub={`按类型的浏览次数占比 · ${current.label}`}
          slices={viewSlices}
          loading={overviewPending}
        />
      </div>
      <div className="rank-stack">
        <div className="rank-card">
          <div className="rank-head">
            <h3>内容榜</h3>
            <a {...rankMoreProps(navigate, RANK_CONTENT, current.trends)}>查看全部</a>
          </div>
          <p className="rank-note">Top 5 · 按热度（{current.label}）</p>
          {contentRank.isPending ? (
            <LoadingHint className="rank-note" />
          ) : (
            <ContentRankGrid
              items={contentRank.data ?? []}
              onOpen={(a) => a.id && navigate(assetDetail(a.id))}
            />
          )}
        </div>
        <div className="rank-grid rank-grid--2">
          <div className="rank-card">
            <div className="rank-head">
              <h3>标签榜</h3>
              <a {...rankMoreProps(navigate, RANK_TAGS, current.trends)}>查看全部</a>
            </div>
            <p className="rank-note">Top 5 · 按窗口浏览</p>
            <RankRowList
              rows={tagRows}
              onSelect={(n) => navigate(collectionPath(COLLECTION_TAG, n))}
            />
          </div>
          <div className="rank-card">
            <div className="rank-head">
              <h3>作者榜</h3>
              <a {...rankMoreProps(navigate, RANK_AUTHORS, current.trends)}>查看全部</a>
            </div>
            <p className="rank-note">Top 5 · 按窗口浏览</p>
            <RankRowList
              rows={authorRows}
              onSelect={(n) => navigate(collectionPath(COLLECTION_AUTHOR, n))}
            />
          </div>
          <div className="rank-card">
            <div className="rank-head">
              <h3>作者总览</h3>
              <a
                href="#"
                id="authorManage"
                onClick={(e) => {
                  e.preventDefault()
                  navigate('/app/authors')
                }}
              >
                管理
              </a>
            </div>
            <p className="rank-note">
              {authors.length} 位作者 · 已关注 {followedCount}
            </p>
            <RankRowList
              rows={authorOverviewRows}
              onSelect={(n) => navigate(collectionPath(COLLECTION_AUTHOR, n))}
            />
          </div>
        </div>
      </div>
    </div>
  )
}
