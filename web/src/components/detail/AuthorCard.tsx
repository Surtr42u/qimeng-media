import { Link } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import type { AssetDetailAuthor } from '@/api/generated'
import { useToggleFollow } from '@/hooks/use-authors'
import { authorDisplayName } from '@/lib/format'
import { provenanceNote } from '@/lib/provenance'
import { ASSETS_QUERY_KEY } from '@/lib/query-keys'
import { collectionPath, COLLECTION_AUTHOR } from '@/lib/route-keys'

/**
 * 详情页作者卡（B站式右栏上部）：作者名 + 关注按钮，多作者逐行，无作者不渲染。
 * 作者名点击进作者文件页（旧版「快速转跳」语义，/app/collection/author/:name）；
 * 关注按钮拦截冒泡防误跳。关注复用 useToggleFollow（失效作者列表），这里补失效
 * 资产根键让详情卡 authors 的 followed 即时刷新。
 * 作者名下次行小字 = 关联溯源注记（ADR-0032 详情面）：词表映射与不可考判定在
 * 逻辑层 provenanceNote，可考才渲染（UI 组件零业务规则，ADR-0008）。
 */
export function AuthorCard({ authors }: { authors: AssetDetailAuthor[] }) {
  const qc = useQueryClient()
  const toggleFollow = useToggleFollow()

  if (authors.length === 0) return null

  return (
    <div className="rank-card author-card">
      <h3>作者</h3>
      <div>
        {authors.map((a) => {
          const note = provenanceNote(a)
          return (
            <div className="author-row" key={a.id ?? a.displayName}>
              <div className="author-main">
                <Link className="author-name" to={collectionPath(COLLECTION_AUTHOR, a.displayName ?? '')}>
                  {authorDisplayName(a)}
                </Link>
                {note ? <span className="prov-note">{note}</span> : null}
              </div>
              <button
                className={`follow-btn${(a.followed ?? false) ? '' : ' follow-btn--idle'}`}
                onClick={(e) => {
                  e.preventDefault()
                  e.stopPropagation()
                  if (!a.id) return
                  toggleFollow.mutate(
                    { authorId: a.id, follow: !(a.followed ?? false) },
                    { onSuccess: () => qc.invalidateQueries({ queryKey: ASSETS_QUERY_KEY }) },
                  )
                }}
              >
                {(a.followed ?? false) ? '已关注' : '+ 关注'}
              </button>
            </div>
          )
        })}
      </div>
    </div>
  )
}
