import { Link, useNavigate, useSearchParams } from 'react-router'
import { Activity, Clock3, Database, Eye, History, FolderOpen, Play, Repeat2, Tags, Users } from 'lucide-react'
import { ReviewMetrics } from '@/components/data/BrowsingReviewContent'
import { DonutCard } from '@/components/data/DonutCard'
import { RankRowList } from '@/components/data/RankRowList'
import { StatsQueryContent } from '@/components/data/StatsQueryContent'
import { TrendChart } from '@/components/data/TrendChart'
import { PageHeading } from '@/components/ui/page-heading'
import { QueryNotice } from '@/components/ui/query-notice'
import { useDataDashboard } from '@/hooks/use-data-dashboard'
import { useBrowsingReview } from '@/hooks/use-browsing-review'
import { LOCALE_ZH } from '@/lib/constants'
import { STATS_SEGMENTS, statsReadMessage } from '@/lib/stats-contract'
import { BROWSING_REVIEW_PATH, AUTHORS_PATH, COLLECTION_AUTHOR, COLLECTION_TAG, MAINTENANCE_FILES_PATH, RANK_AUTHORS, RANK_TAGS, collectionPath, rankPath } from '@/lib/route-keys'

const METRIC_ICONS: Record<string, typeof Eye> = { views: Eye, plays: Play, time: Clock3, average: Repeat2 }

/** 窗口活动与当前库存明确分区；任何板块失败均不遮住其余内容。 */
export default function DataPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const current = STATS_SEGMENTS.find(segment => segment.trends === params.get('range')) ?? STATS_SEGMENTS[0]
  const data = useDataDashboard(current)
  const { overviewQuery, overview, trendQuery, tagQuery, authorQuery, authorsQuery } = data
  const reviewQuery = useBrowsingReview(current.trends)
  const selectRange = (range: string) => setParams(previous => { const next = new URLSearchParams(previous); next.set('range', range); return next }, { replace: true })

  return <div className="page personal-workspace data-workspace" id="page-data">
    <PageHeading title="数据" description="浏览习惯、内容热度与媒体库概况" actions={<Link className="workspace-link" to={MAINTENANCE_FILES_PATH}><FolderOpen aria-hidden="true" />数据管理</Link>} />
    <div className="data-window-bar"><div className="workspace-segments" role="group" aria-label="统计时段">{STATS_SEGMENTS.map(segment => <button className={current.trends === segment.trends ? 'active' : ''} key={segment.trends} type="button" aria-pressed={current.trends === segment.trends} onClick={() => selectRange(segment.trends)}>{segment.label}</button>)}</div><span>{current.note}</span></div>
    {overviewQuery.isError && <QueryNotice message={statsReadMessage(overviewQuery.error, '统计概览')} retained={!!overview} busy={overviewQuery.isFetching} onRetry={() => { void overviewQuery.refetch() }} />}
    <div className="data-metrics" role="group" aria-label="所选时段指标">{data.metrics.map(metric => {
      const Icon = METRIC_ICONS[metric.key] ?? Activity
      return <section className={`metric-card data-metric data-metric--${metric.key}`} key={metric.key}><div className="data-metric-label"><Icon aria-hidden="true" /><span>{metric.label}</span></div><strong>{metric.value}</strong><small>{metric.note}</small></section>
    })}</div>
    <div className="data-primary-grid">
      <section className="chart-card data-trend-panel" aria-label="浏览与播放趋势">
        <div className="workspace-card-head"><h3><Activity aria-hidden="true" />浏览与播放趋势</h3><span>{current.label}</span></div>
        <p className="rank-note">浏览和播放分别统计 · {current.note}</p>
        {overview && <div className="data-coverage"><span>看过 <b>{overview.viewedFilesCount.toLocaleString(LOCALE_ZH)}</b> 个文件</span><span>涉及 <b>{overview.viewedAuthorsCount.toLocaleString(LOCALE_ZH)}</b> 位作者</span><span><b>{overview.viewedTagsCount.toLocaleString(LOCALE_ZH)}</b> 个标签</span></div>}
        <StatsQueryContent query={trendQuery} label="浏览趋势">{data.trendAllZero ? <p className="workspace-empty-note">暂无趋势数据</p> : <TrendChart buckets={trendQuery.data ?? []} />}</StatsQueryContent>
      </section>
      <section className="chart-card data-inventory-panel" aria-label="媒体库概况">
        <div className="workspace-card-head"><h3><Database aria-hidden="true" />媒体库概况</h3><span>当前库存</span></div>
        <p className="rank-note">不随上方统计时段改变</p>
        <dl className="data-inventory-metrics"><div><dt>总文件</dt><dd>{data.inventoryFiles}</dd></div><div><dt>总容量</dt><dd>{data.inventoryBytes}</dd></div></dl>
        <h4>内容类型分布</h4>
        {data.typeSlices.length ? <><div className="data-type-bar" aria-hidden="true">{data.typeSlices.map(slice => <i key={slice.name} className={slice.dot} style={{ width: `${slice.pct}%` }} />)}</div><ul className="legend data-inventory-types">{data.typeSlices.map(slice => <li key={slice.name}><i className={`dot ${slice.dot}`} /><span>{slice.name}</span><b>{slice.value}</b><small>{slice.pct.toFixed(1)}%</small></li>)}</ul></> : <p className="rank-note">{overviewQuery.isPending ? '正在读取库存…' : overview?.totalFiles === 0 ? '暂无库存文件' : '分类信息暂不可用'}</p>}
      </section>
    </div>
    <div className="data-secondary-grid">
      <section className="rank-card data-content-panel" aria-label="浏览回顾">
        <div className="workspace-card-head"><h3><History aria-hidden="true" />浏览回顾</h3><Link to={`${BROWSING_REVIEW_PATH}?range=${current.trends}`}>查看回顾</Link></div><p className="rank-note">探索与回访 · 兴趣变化 · 浏览习惯</p>
        <StatsQueryContent query={reviewQuery} label="浏览回顾">{reviewQuery.data && <ReviewMetrics review={reviewQuery.data} />}</StatsQueryContent>
      </section>
      {overview || overviewQuery.isPending ? <DonutCard title="浏览量占比" sub={`按类型的浏览次数 · ${current.label}`} slices={data.viewSlices} loading={overviewQuery.isPending} emptyText="此窗口暂无浏览记录" centerValue={overview?.windowTotals.viewCount.toLocaleString(LOCALE_ZH)} centerLabel="浏览次数" /> : <section className="donut-card"><h3>浏览量占比</h3><p className="rank-note">统计概览尚不可用，恢复读取后显示。</p></section>}
    </div>
    <div className="data-rank-grid">
      <section className="rank-card" aria-label="标签榜"><div className="workspace-card-head"><h3><Tags aria-hidden="true" />标签榜</h3><Link to={rankPath(RANK_TAGS, current.trends)}>查看全部</Link></div><p className="rank-note">Top 5 · 窗口浏览次数</p><StatsQueryContent query={tagQuery} label="标签榜"><RankRowList rows={data.tagRows} onSelect={name => navigate(collectionPath(COLLECTION_TAG, name))} /></StatsQueryContent></section>
      <section className="rank-card" aria-label="作者榜"><div className="workspace-card-head"><h3><Users aria-hidden="true" />作者榜</h3><Link to={rankPath(RANK_AUTHORS, current.trends)}>查看全部</Link></div><p className="rank-note">Top 5 · 窗口浏览次数</p><StatsQueryContent query={authorQuery} label="作者榜"><RankRowList rows={data.authorRows} onSelect={name => navigate(collectionPath(COLLECTION_AUTHOR, name))} /></StatsQueryContent></section>
      <section className="rank-card" aria-label="作者总览"><div className="workspace-card-head"><h3><Users aria-hidden="true" />作者总览</h3><Link to={AUTHORS_PATH}>管理作者</Link></div><p className="rank-note">{authorsQuery.data ? `${authorsQuery.data.length} 位作者 · 已关注 ${data.followedCount}` : '作者管理预览'}</p><StatsQueryContent query={authorsQuery} label="作者总览"><RankRowList rows={data.authorOverviewRows} onSelect={name => navigate(collectionPath(COLLECTION_AUTHOR, name))} /></StatsQueryContent></section>
    </div>
  </div>
}
