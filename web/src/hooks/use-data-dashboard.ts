import { useMemo } from 'react'
import type { DonutSlice } from '@/components/data/DonutCard'
import { useAuthors } from './use-authors'
import { useStatsOverview, useTrends, useStatsTopAuthors, useStatsTopTags } from './use-stats'
import { LOCALE_ZH } from '@/lib/constants'
import { authorDisplayName, formatBytes, formatDurationSeconds } from '@/lib/format'
import { authorRankRows, tagRankRows } from '@/lib/rank-rows'
import { STATS_SEGMENTS } from '@/lib/stats-contract'

const RANK_TOP_PREVIEW_COUNT = 5

/** 仅组织服务端统计及展示格式；不计算领域指标，不用管理列表代替窗口榜单。 */
export function useDataDashboard(current: typeof STATS_SEGMENTS[number]) {
  const overviewQuery = useStatsOverview(current.trends)
  const trendQuery = useTrends(current.trends)
  const tagQuery = useStatsTopTags(current.trends, RANK_TOP_PREVIEW_COUNT)
  const authorQuery = useStatsTopAuthors(current.trends, RANK_TOP_PREVIEW_COUNT)
  const authorsQuery = useAuthors()
  const overview = overviewQuery.data
  const metrics = [
    { key: 'views', label: '总浏览次数', value: overview ? overview.windowTotals.viewCount.toLocaleString(LOCALE_ZH) : '—', note: '作品详情的浏览记录' },
    { key: 'plays', label: '总播放次数', value: overview ? overview.windowTotals.playCount.toLocaleString(LOCALE_ZH) : '—', note: '视频播放记录' },
    { key: 'time', label: '总浏览时长', value: overview ? formatDurationSeconds(overview.windowTotals.seconds) : '—', note: '详情页的有效停留时间' },
    { key: 'average', label: '平均浏览次数', value: overview?.avgViewsPerFile == null ? '—' : overview.avgViewsPerFile.toFixed(1), note: '每个被浏览过的现存文件' },
  ]
  const typeSlices = useMemo<DonutSlice[]>(() => {
    if (!overview?.totalFiles) return []
    return [
      { name: '图片', count: overview.stillImageCount, dot: 'd2', arc: 'arc-2' },
      { name: '视频', count: overview.videoCount ?? 0, dot: 'd1', arc: 'arc-1' },
      { name: '动图', count: overview.animatedImageCount, dot: 'd4', arc: 'arc-4' },
    ].map(({ name, count, dot, arc }) => ({ name, dot, arc, value: count.toLocaleString(LOCALE_ZH), pct: count / overview.totalFiles! * 100 }))
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
  const tagRows = useMemo(() => tagRankRows(tagQuery.data ?? []), [tagQuery.data])
  const authorRows = useMemo(() => authorRankRows(authorQuery.data ?? []), [authorQuery.data])
  const authorOverviewRows = useMemo(() => (authorsQuery.data ?? []).slice()
    .sort((a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0)).slice(0, RANK_TOP_PREVIEW_COUNT)
    .map(author => ({ name: authorDisplayName(author), count: '' })), [authorsQuery.data])
  return {
    overviewQuery, trendQuery, tagQuery, authorQuery, authorsQuery, overview,
    metrics, typeSlices, viewSlices, tagRows, authorRows, authorOverviewRows,
    inventoryFiles: overview?.totalFiles == null ? '—' : overview.totalFiles.toLocaleString(LOCALE_ZH),
    inventoryBytes: overview?.totalSizeBytes == null ? '—' : formatBytes(overview.totalSizeBytes),
    followedCount: (authorsQuery.data ?? []).filter(author => author.followed).length,
    trendAllZero: !trendQuery.data || trendQuery.data.every(bucket => (bucket.viewCount ?? 0) === 0 && (bucket.playCount ?? 0) === 0),
  }
}
