import type { QueryClient } from '@tanstack/react-query'
import {
  ASSETS_QUERY_KEY, AUTHORS_QUERY_KEY, DIRS_QUERY_KEY, HISTORY_QUERY_KEY,
  LIBRARIES_QUERY_KEY, RANKINGS_QUERY_KEY, RECOMMENDATIONS_QUERY_KEY,
  SOURCES_QUERY_KEY, STATS_QUERY_KEY, TAGS_QUERY_KEY, TRASH_QUERY_KEY,
} from './query-keys'

/** 库变更/导入/重连共用：持续挂载的统计和榜单也必须重新取权威数据。
 * staleTime 只标记陈旧，不会为仍挂载的查询自动定时重取。 */
export function invalidateLibraryContent(queryClient: QueryClient): void {
  for (const key of [
    ASSETS_QUERY_KEY, LIBRARIES_QUERY_KEY, DIRS_QUERY_KEY, TAGS_QUERY_KEY,
    AUTHORS_QUERY_KEY, SOURCES_QUERY_KEY, TRASH_QUERY_KEY,
    RECOMMENDATIONS_QUERY_KEY, STATS_QUERY_KEY, RANKINGS_QUERY_KEY, HISTORY_QUERY_KEY,
  ]) {
    void queryClient.invalidateQueries({ queryKey: key })
  }
}
