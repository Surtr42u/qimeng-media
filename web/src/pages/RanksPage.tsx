import { useMemo } from 'react'
import { useNavigate, useParams } from 'react-router'
import { ContentRankGrid } from '@/components/data/ContentRankGrid'
import { RankRowList } from '@/components/data/RankRowList'
import { useAuthors } from '@/hooks/use-authors'
import { useRankings } from '@/hooks/use-stats'
import { useTags } from '@/hooks/use-tags'
import { MAX_PAGE_SIZE } from '@/lib/constants'
import { authorRankRows, tagRankRows } from '@/lib/rank-rows'
import {
  assetDetail,
  COLLECTION_AUTHOR,
  COLLECTION_TAG,
  RANK_AUTHORS,
  RANK_CONTENT,
  RANK_PAGE_TITLES,
  RANK_TAGS,, collectionPath } from '@/lib/route-keys'

type RankKey = typeof RANK_CONTENT | typeof RANK_TAGS | typeof RANK_AUTHORS

const RANK_KEYS: readonly string[] = [RANK_CONTENT, RANK_TAGS, RANK_AUTHORS]

/**
 * 完整榜单页（原型 #page-ranks 移植，阶段 B 已接真实数据）：数据页排行卡「查看全部」
 * 按榜进入，只渲染点进的那一个榜（原型 data-panel 匹配语义），非法 rank 按 content 处理。
 * 内容榜固定全周期（现状无周期胶囊——任务说明：无则不新增）；标签/作者为全量降序列表。
 * F7（2026-09-08）：删内层 .rank-card 的 .rank-head 重复标题（h3 与 page-head h2
 * 双框双标题，用户反馈）——page-head 大标题为唯一标题，内层只留框体（承担列表
 * 布局/底色，保留）+ 口径注 + 列表，节奏对齐集合子页「page-head + 单框内容区」。
 */
export default function RanksPage() {
  const { rank } = useParams()
  const navigate = useNavigate()
  const key: RankKey = RANK_KEYS.includes(rank ?? '') ? (rank as RankKey) : RANK_CONTENT

  const contentRank = useRankings('all', MAX_PAGE_SIZE)
  const { data: tags = [] } = useTags()
  const { data: authors = [] } = useAuthors()

  // 标签/作者行加工口径单源在 lib/rank-rows.ts（DOMAIN_RULES 口径）；完整榜单页全量展示
  const tagRows = useMemo(() => tagRankRows(tags), [tags])
  const authorRows = useMemo(() => authorRankRows(authors), [authors])

  return (
    <div className="page" id="page-ranks">
      <div className="page-head">
        <h2>{RANK_PAGE_TITLES[key]}</h2>
        <p>全量排行</p>
      </div>
      {key === RANK_CONTENT ? (
        <div className="rank-card">
          <p className="rank-note">按浏览量</p>
          <ContentRankGrid
            items={contentRank.data ?? []}
            onOpen={(a) => a.id && navigate(assetDetail(a.id))}
          />
        </div>
      ) : (
        <div className="rank-card">
          <p className="rank-note">{key === RANK_TAGS ? '按关联文件数' : '按浏览'}</p>
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
