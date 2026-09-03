import { useMemo, useState, type MouseEvent } from 'react'
import { useNavigate } from 'react-router'
import type { StatsOverview, TrendBucket } from '@/api/generated'
import { ContentRankGrid } from '@/components/data/ContentRankGrid'
import { DonutCard, type DonutSlice } from '@/components/data/DonutCard'
import { RankRowList } from '@/components/data/RankRowList'
import { TrendChart } from '@/components/data/TrendChart'
import { useAuthors } from '@/hooks/use-authors'
import { useRankings, useStatsOverview, useTrends, type RankingPeriod, type TrendsRange } from '@/hooks/use-stats'
import { useTags } from '@/hooks/use-tags'
import { LOCALE_ZH } from '@/lib/constants'
import { formatBytes } from '@/lib/format'
import { COLLECTION_AUTHOR, COLLECTION_TAG, RANK_AUTHORS, RANK_CONTENT, RANK_TAGS } from '@/lib/route-keys'

/**
 * 数据页（原型 #page-data 移植，阶段 B 已接真实数据）：时段胶囊 + 6 指标卡 +
 * 趋势折线 + 双环分布 + 排行榜（Top5/标签/作者/作者总览）。
 * 时段四档（近 7/30/90 天/全部）同档联动趋势 range 与内容榜 period，其余卡不随档变化；
 * 指标/双环来自 GET /stats/overview 与 /stats/trends（DOMAIN_RULES §5 口径）。
 */

/** 时段四档（用户拍板）：趋势 range → 内容榜 period 同档联动（映射依据 DOMAIN_RULES §5 窗口表） */
const SEGMENTS: { label: string; note: string; trends: TrendsRange; period: RankingPeriod }[] = [
  { label: '近 7 天', note: '近 7 天 · 逐日', trends: '7d', period: 'week' },
  { label: '近 30 天', note: '近 30 天 · 逐日', trends: 'day', period: 'month' },
  { label: '近 90 天', note: '近 90 天 · 逐日', trends: '90d', period: 'quarter' },
  { label: '全部', note: '全部 · 动态分桶', trends: 'all', period: 'all' },
]

/** 默认档（原型打开即「7 天」，此处映射近 7 天） */
const DEFAULT_SEG = 0

/** 内容榜/标签榜/作者榜展示条数（Top 5） */
const RANK_TOP_COUNT = 5

/** 6 指标卡（label 固定；值全部来自 GET /stats/overview；无涨跌数据源，delta 不渲染） */
const METRIC_DEFS: { label: string; pick: (s: StatsOverview) => string }[] = [
  { label: '总文件', pick: (s) => (s.totalFiles ?? 0).toLocaleString(LOCALE_ZH) },
  { label: '图片', pick: (s) => (s.imageCount ?? 0).toLocaleString(LOCALE_ZH) },
  { label: '视频', pick: (s) => (s.videoCount ?? 0).toLocaleString(LOCALE_ZH) },
  { label: '总容量', pick: (s) => formatBytes(s.totalSizeBytes ?? 0) },
  { label: '今日浏览', pick: (s) => (s.todayViews ?? 0).toLocaleString(LOCALE_ZH) },
  { label: '累计浏览', pick: (s) => (s.totalViews ?? 0).toLocaleString(LOCALE_ZH) },
]

/** 分桶 viewCount 求和（浏览量占比用） */
function sumViews(items?: TrendBucket[]): number {
  return items?.reduce((acc, b) => acc + (b.viewCount ?? 0), 0) ?? 0
}

/** 「查看全部」跳完整榜单子页（保留原型 a 标签与 data-rank，拦截默认锚点跳转） */
function rankMoreProps(navigate: ReturnType<typeof useNavigate>, rank: string) {
  return {
    href: '#',
    className: 'rank-more',
    'data-rank': rank,
    onClick: (e: MouseEvent<HTMLAnchorElement>) => {
      e.preventDefault()
      navigate(`/app/ranks/${rank}`)
    },
  }
}

export default function DataPage() {
  const navigate = useNavigate()
  const [seg, setSeg] = useState(DEFAULT_SEG)

  const current = SEGMENTS[seg]

  const { data: overview, isPending: overviewPending } = useStatsOverview()
  const trendQuery = useTrends(current.trends)
  const videoTrend = useTrends('day', 'video')
  const imageTrend = useTrends('day', 'image')
  const contentRank = useRankings(current.period, RANK_TOP_COUNT)
  const { data: tags = [] } = useTags()
  const { data: authors = [] } = useAuthors()

  // 内容类型分布：动画图计入 imageCount（DOMAIN_RULES/GUIDE_API 口径）
  const typeSlices = useMemo<DonutSlice[]>(() => {
    const total = overview?.totalFiles ?? 0
    if (!total) return []
    const video = overview?.videoCount ?? 0
    const image = overview?.imageCount ?? 0
    return [
      { dot: 'd1', arc: 'arc-1', name: '视频', value: video.toLocaleString(LOCALE_ZH), pct: (video / total) * 100 },
      { dot: 'd2', arc: 'arc-2', name: '图片', value: image.toLocaleString(LOCALE_ZH), pct: (image / total) * 100 },
    ]
  }, [overview])

  // 浏览量占比：近 30 天口径（range=day）按类型分桶求和——随时段切换不联动（用户拍板）
  const viewSlices = useMemo<DonutSlice[]>(() => {
    const video = sumViews(videoTrend.data)
    const image = sumViews(imageTrend.data)
    const total = video + image
    if (!total) return []
    return [
      { dot: 'd4', arc: 'arc-4', name: '视频', value: video.toLocaleString(LOCALE_ZH), pct: (video / total) * 100 },
      { dot: 'd2', arc: 'arc-2', name: '图片', value: image.toLocaleString(LOCALE_ZH), pct: (image / total) * 100 },
    ]
  }, [videoTrend.data, imageTrend.data])

  // 标签/作者榜：按 fileCount 降序取 Top5（行式列表，点击进集合子页）
  const tagRows = useMemo(
    () =>
      tags
        .slice()
        .sort((a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0))
        .slice(0, RANK_TOP_COUNT)
        .map((t) => ({ name: t.name ?? '', count: String(t.fileCount ?? 0) })),
    [tags],
  )
  const authorRows = useMemo(
    () =>
      authors
        .slice()
        .sort((a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0))
        .slice(0, RANK_TOP_COUNT)
        .map((a) => ({ name: a.displayName ?? '', count: String(a.fileCount ?? 0) })),
    [authors],
  )
  const followedCount = useMemo(() => authors.filter((a) => a.followed).length, [authors])

  // 趋势全 0（含空桶）→ 暂无趋势数据（DOMAIN_RULES §5 空数据口径）
  const trendAllZero =
    !trendQuery.data ||
    trendQuery.data.every((b) => (b.viewCount ?? 0) === 0 && (b.playCount ?? 0) === 0)

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
          <p className="m-note">加载中…</p>
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
          sub="按类型的浏览次数占比 · 近 30 天"
          slices={viewSlices}
          loading={videoTrend.isPending || imageTrend.isPending}
        />
      </div>
      <div className="rank-stack">
        <div className="rank-card">
          <div className="rank-head">
            <h3>内容榜</h3>
            <a {...rankMoreProps(navigate, RANK_CONTENT)}>查看全部</a>
          </div>
          <p className="rank-note">Top 5 · 按浏览量（{current.label}）</p>
          {contentRank.isPending ? (
            <p className="rank-note">加载中…</p>
          ) : (
            <ContentRankGrid
              items={contentRank.data ?? []}
              onOpen={(a) => a.id && navigate(`/app/asset/${a.id}`)}
            />
          )}
        </div>
        <div className="rank-grid rank-grid--2">
          <div className="rank-card">
            <div className="rank-head">
              <h3>标签榜</h3>
              <a {...rankMoreProps(navigate, RANK_TAGS)}>查看全部</a>
            </div>
            <p className="rank-note">Top 5 · 按关联文件数</p>
            <RankRowList
              rows={tagRows}
              onSelect={(n) => navigate(`/app/collection/${COLLECTION_TAG}/${encodeURIComponent(n)}`)}
            />
          </div>
          <div className="rank-card">
            <div className="rank-head">
              <h3>作者榜</h3>
              <a {...rankMoreProps(navigate, RANK_AUTHORS)}>查看全部</a>
            </div>
            <p className="rank-note">Top 5 · 按作品数</p>
            <RankRowList
              rows={authorRows}
              onSelect={(n) => navigate(`/app/collection/${COLLECTION_AUTHOR}/${encodeURIComponent(n)}`)}
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
              rows={authorRows}
              onSelect={(n) => navigate(`/app/collection/${COLLECTION_AUTHOR}/${encodeURIComponent(n)}`)}
            />
          </div>
        </div>
      </div>
    </div>
  )
}
