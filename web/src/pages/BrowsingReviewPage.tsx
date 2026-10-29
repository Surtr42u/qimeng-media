import { Link, useSearchParams } from 'react-router'
import { BrowsingReviewContent } from '@/components/data/BrowsingReviewContent'
import { StatsQueryContent } from '@/components/data/StatsQueryContent'
import { PageHeading } from '@/components/ui/page-heading'
import { useBrowsingReview } from '@/hooks/use-browsing-review'
import { DATA_PATH } from '@/lib/route-keys'
import { STATS_SEGMENTS } from '@/lib/stats-contract'

export default function BrowsingReviewPage() {
  const [params, setParams] = useSearchParams()
  const current = STATS_SEGMENTS.find(segment => segment.trends === params.get('range')) ?? STATS_SEGMENTS[0]
  const query = useBrowsingReview(current.trends)
  return <div className="page personal-workspace data-workspace">
    <PageHeading title="浏览回顾" description="看看自己如何探索、回访，以及兴趣如何变化" actions={<Link className="workspace-link" to={`${DATA_PATH}?range=${current.trends}`}>返回数据</Link>} />
    <div className="data-window-bar"><div className="workspace-segments" role="group" aria-label="回顾时段">{STATS_SEGMENTS.map(segment => <button key={segment.trends} type="button" className={current.trends === segment.trends ? 'active' : ''} aria-pressed={current.trends === segment.trends} onClick={() => setParams({ range: segment.trends }, { replace: true })}>{segment.label}</button>)}</div></div>
    <StatsQueryContent query={query} label="浏览回顾">{query.data && <BrowsingReviewContent review={query.data} />}</StatsQueryContent>
  </div>
}
