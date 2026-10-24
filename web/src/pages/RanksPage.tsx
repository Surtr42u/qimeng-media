import { useMemo } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { ContentRankGrid } from '@/components/data/ContentRankGrid'
import { RankRowList } from '@/components/data/RankRowList'
import { useRankings, useStatsTopAuthors, useStatsTopTags } from '@/hooks/use-stats'
import { STATS_SEGMENTS, STATS_RANK_LIMIT, STATS_LOAD_FAILED_MESSAGE } from '@/lib/stats-contract'
import { LoadingHint } from '@/components/ui/loading-hint'
import { MAX_PAGE_SIZE } from '@/lib/constants'
import { authorRankRows, tagRankRows } from '@/lib/rank-rows'
import {
  assetDetail,
  COLLECTION_AUTHOR,
  COLLECTION_TAG,
  RANK_AUTHORS,
  RANK_CONTENT,
  RANK_PAGE_TITLES,
  RANK_TAGS, collectionPath } from '@/lib/route-keys'

type RankKey = typeof RANK_CONTENT | typeof RANK_TAGS | typeof RANK_AUTHORS

const RANK_KEYS: readonly string[] = [RANK_CONTENT, RANK_TAGS, RANK_AUTHORS]

/**
 * 完整榜单页（原型 #page-ranks 移植，阶段 B 已接真实数据）：数据页排行卡「查看全部」
 * 按榜进入，只渲染点进的那一个榜（原型 data-panel 匹配语义），非法 rank 按 content 处理。
 * 沿用入口时间窗口；榜单使用服务端排序与上限，不把有限结果标为全量。
 * F7（2026-09-08）：删内层 .rank-card 的 .rank-head 重复标题（h3 与 page-head h2
 * 双框双标题，用户反馈）——page-head 大标题为唯一标题，内层只留框体（承担列表
 * 布局/底色，保留）+ 口径注 + 列表，节奏对齐集合子页「page-head + 单框内容区」。
 */
export default function RanksPage() {
  const { rank } = useParams()
  const navigate = useNavigate()
  const key: RankKey = RANK_KEYS.includes(rank ?? '') ? (rank as RankKey) : RANK_CONTENT

  const [search] = useSearchParams()
  const segment = STATS_SEGMENTS.find((item) => item.trends === search.get('range')) ?? STATS_SEGMENTS[3]
  const contentRank = useRankings(segment.period, MAX_PAGE_SIZE)
  const tagsQuery = useStatsTopTags(segment.trends, STATS_RANK_LIMIT)
  const authorsQuery = useStatsTopAuthors(segment.trends, STATS_RANK_LIMIT)
  const tags = tagsQuery.data ?? []
  const authors = authorsQuery.data ?? []
  const active = key === RANK_CONTENT ? contentRank : key === RANK_TAGS ? tagsQuery : authorsQuery

  // 标签/作者行加工口径单源在 lib/rank-rows.ts（DOMAIN_RULES 口径）；完整榜单页全量展示
  const tagRows = useMemo(() => tagRankRows(tags), [tags])
  const authorRows = useMemo(() => authorRankRows(authors), [authors])

  return (
    <div className="page" id="page-ranks">
      <div className="page-head">
        <h2>{RANK_PAGE_TITLES[key]}</h2>
        <p>{segment.label} · {key === RANK_CONTENT ? `前 ${MAX_PAGE_SIZE}` : `前 ${STATS_RANK_LIMIT}`} 项</p>
      </div>
      {active.isError ? <div role="alert"><p>{STATS_LOAD_FAILED_MESSAGE}</p><button type="button" className="pill" onClick={() => void active.refetch()}>重试</button></div>
        : active.isPending ? <LoadingHint /> : key === RANK_CONTENT ? (
        <div className="rank-card">
          <p className="rank-note">按热度</p>
          <ContentRankGrid
            items={contentRank.data ?? []}
            onOpen={(a) => a.id && navigate(assetDetail(a.id))}
          />
        </div>
      ) : (
        <div className="rank-card">
          <p className="rank-note">按窗口浏览</p>
          <RankRowList
            rows={key === RANK_TAGS ? tagRows : authorRows}
            onSelect={(n) =>
              navigate(
                collectionPath(key === RANK_TAGS ? COLLECTION_TAG : COLLECTION_AUTHOR, n),
              )
            }
          />
        </div>
      )}
    </div>
  )
}
