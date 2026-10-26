import { useMemo } from 'react'
import { useAssetsInfinite } from './use-assets'
import { useAuthors, useToggleFollow } from './use-authors'
import { useHistoryInfinite } from './use-history'
import { useLibraries } from './use-libraries'
import { useStatsOverview } from './use-stats'
import { useAutoMore } from './use-auto-more'
import { DEFAULT_PAGE_SIZE } from '@/lib/constants'
import { groupHistory } from '@/lib/history-grouping'

export type MineTab = 'follow' | 'fav' | 'history'

/** 关注/收藏/历史保持原数据源及分页口径；本地筛选期间改为明确续页，避免自动扫完整个历史。 */
export function useMineWorkspace(tab: MineTab, filter: string) {
  const librariesQuery = useLibraries()
  const overviewQuery = useStatsOverview()
  const authorsQuery = useAuthors()
  const toggleFollow = useToggleFollow()
  const favQuery = useAssetsInfinite({ favorite: true, sort: 'favoriteAt', order: 'desc' }, tab === 'fav')
  const histQuery = useHistoryInfinite(DEFAULT_PAGE_SIZE, tab === 'history')
  const favItems = useMemo(() => favQuery.data?.pages.flatMap(page => page.items ?? []) ?? [], [favQuery.data])
  const histItems = useMemo(() => histQuery.data?.pages.flatMap(page => page.items ?? []) ?? [], [histQuery.data])
  const followedAuthors = useMemo(() => (authorsQuery.data ?? []).filter(author => author.followed), [authorsQuery.data])
  const histRenderGroups = useMemo(() => groupHistory(histItems, filter).map(group => ({ ...group, items: group.items.map(item => ({
    ...item,
    // 与既有完成徽标逐字同义：仅在视频时长/播放位置都存在时比较。
    isDone: item.durationMs != null && item.lastPositionSeconds != null && item.lastPositionSeconds * 1000 >= item.durationMs,
  })) })), [histItems, filter])
  const favSentinelRef = useAutoMore(favQuery.hasNextPage && !favQuery.isError, () => {
    if (!favQuery.isFetching && !favQuery.isPlaceholderData) void favQuery.fetchNextPage({ cancelRefetch: false })
  })
  const histSentinelRef = useAutoMore(histQuery.hasNextPage && !histQuery.isError && !filter.trim(), () => {
    if (!histQuery.isFetching && !histQuery.isPlaceholderData) void histQuery.fetchNextPage({ cancelRefetch: false })
  })
  const histFilteredCount = histRenderGroups.reduce((count, group) => count + group.items.length, 0)
  return { librariesQuery, overviewQuery, authorsQuery, toggleFollow, favQuery, histQuery, favItems, histItems, followedAuthors, histRenderGroups, histFilteredCount, favSentinelRef, histSentinelRef }
}
