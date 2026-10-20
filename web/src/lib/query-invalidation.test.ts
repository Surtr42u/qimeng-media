import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { invalidateLibraryContent } from './query-invalidation'
import { HISTORY_QUERY_KEY, RANKINGS_QUERY_KEY, STATS_QUERY_KEY } from './query-keys'

describe('库内容变更缓存刷新', () => {
  it('已挂载统计立即重取；缓存中的历史和不同形态排行榜同时失效', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } })
    const overview = [...STATS_QUERY_KEY, 'overview']
    const history = [...HISTORY_QUERY_KEY, 60]
    const ranking = [...RANKINGS_QUERY_KEY, 'paged', 'day', 60, 0]
    client.setQueryData(overview, 3)
    client.setQueryData(history, ['deleted'])
    client.setQueryData(ranking, ['deleted'])
    let reads = 0
    const observer = new QueryObserver(client, { queryKey: overview, queryFn: async () => { reads++; return 2 } })
    const unsubscribe = observer.subscribe(() => {})
    try {
      invalidateLibraryContent(client)
      await client.refetchQueries({ queryKey: overview, type: 'active' }, { cancelRefetch: false })
      expect(reads).toBe(1)
      expect(client.getQueryData(overview)).toBe(2)
      expect(client.getQueryState(history)?.isInvalidated).toBe(true)
      expect(client.getQueryState(ranking)?.isInvalidated).toBe(true)
      expect(client.getQueryCache().findAll()).toHaveLength(3)
    } finally { unsubscribe(); client.clear() }
  })
})
