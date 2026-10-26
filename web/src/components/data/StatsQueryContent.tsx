import type { ReactNode } from 'react'
import { LoadingHint } from '@/components/ui/loading-hint'
import { QueryNotice } from '@/components/ui/query-notice'
import { statsReadMessage } from '@/lib/stats-contract'

/** 统计子板块独立失败；有缓存时标记旧内容，无缓存时不误报空榜。 */
export function StatsQueryContent({ query, label, children }: {
  query: { data: unknown; error: Error | null; isError: boolean; isPending: boolean; isFetching: boolean; refetch: () => unknown }
  label: string; children: ReactNode
}) {
  return <>{query.isError && <QueryNotice message={statsReadMessage(query.error, label)} retained={query.data != null} busy={query.isFetching} onRetry={() => { void query.refetch() }} />}
    {query.isPending ? <LoadingHint className="rank-note" /> : query.data == null ? null : children}
  </>
}
