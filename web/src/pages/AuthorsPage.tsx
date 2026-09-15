import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import type { Author } from '@/api/generated'
import { SearchIcon } from '@/components/shell/icons'
import { useAuthors, useToggleFollow } from '@/hooks/use-authors'
import { authorDisplayName } from '@/lib/format'
import { collectionPath, COLLECTION_AUTHOR } from '@/lib/route-keys'

type AuthorZone = '全部' | '常规' | 'cos'
type AuthorSort = 'default' | 'browse' | 'works'

/** 体系胶囊：data-zone 值与作者 type 字段对应（cos 小写），展示文案 COS 大写 */
const ZONES: { zone: AuthorZone; label: string }[] = [
  { zone: '全部', label: '全部' },
  { zone: '常规', label: '常规' },
  { zone: 'cos', label: 'COS' },
]

const SORTS: { key: AuthorSort; label: string }[] = [
  { key: 'default', label: '默认' },
  { key: 'browse', label: '经常浏览' },
  { key: 'works', label: '文件数量' },
]

/** 体系胶囊 → 协议 Author.type（「全部」不筛） */
const ZONE_TO_TYPE: Record<AuthorZone, Author['type'] | undefined> = {
  全部: undefined,
  常规: 'regular',
  cos: 'cos',
}

// 照 app.js A_SORTERS：默认=API 原序、浏览数降序、文件数降序
const A_SORTERS: Record<AuthorSort, (a: Author, b: Author) => number> = {
  default: () => 0,
  browse: (a, b) => (b.viewCount ?? 0) - (a.viewCount ?? 0),
  works: (a, b) => (b.fileCount ?? 0) - (a.fileCount ?? 0),
}

/**
 * 作者管理页（原型 #page-authors 移植，阶段 B 已接真实数据）：体系胶囊 + 名字搜索 +
 * 排序三项 + 关注按钮（PUT /authors/{authorId}/follow，状态以服务端 followed 为准）。
 * 列表行可点（2026-09-05 补同族缺口）：进作者集合子页 /app/collection/author/{displayName}
 * （原始 displayName 不带「 ·COS」展示后缀，URL 编码）；可点视觉复用现有类
 * （.rank-card li 自带 cursor:pointer + 悬停底色，不发明新样式）。
 */
export default function AuthorsPage() {
  const navigate = useNavigate()
  const { data: authors = [], isLoading } = useAuthors()
  const toggleFollow = useToggleFollow()
  const [zone, setZone] = useState<AuthorZone>('全部')
  const [keyword, setKeyword] = useState('')
  const [sort, setSort] = useState<AuthorSort>('default')

  // 先 filter 后 sort，slice 避免原地排序污染 filter 结果顺序基准（照 app.js renderAuthors）
  const rows = useMemo(() => {
    const kw = keyword.trim()
    const type = ZONE_TO_TYPE[zone]
    return authors
      .filter(
        (a) =>
          (type === undefined || a.type === type) &&
          (!kw || (a.displayName ?? '').includes(kw)),
      )
      .slice()
      .sort(A_SORTERS[sort])
  }, [authors, zone, keyword, sort])

  return (
    <div className="page" id="page-authors">
      <div className="page-head">
        <h2>作者管理</h2>
        <p>全部作者 · {authors.length} 位</p>
      </div>
      <div className="a-toolbar">
        <div className="a-zones">
          {ZONES.map((z) => (
            <button
              key={z.zone}
              type="button"
              className={`stype${zone === z.zone ? ' active' : ''}`}
              data-zone={z.zone}
              onClick={() => setZone(z.zone)}
            >
              {z.label}
            </button>
          ))}
        </div>
        <div className="hist-search">
          <input
            type="text"
            placeholder="搜索作者"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <SearchIcon />
        </div>
      </div>
      <div className="a-sortrow">
        {SORTS.map((s) => (
          <button
            key={s.key}
            type="button"
            className={`sort-pill${sort === s.key ? ' active' : ''}`}
            data-sort={s.key}
            onClick={() => setSort(s.key)}
          >
            {s.label}
          </button>
        ))}
      </div>
      <div className="rank-card a-list">
        {isLoading ? (
          <p className="a-empty">加载中…</p>
        ) : rows.length ? (
          <ul>
            {rows.map((a) => (
              <li
                key={a.id}
                onClick={() =>
                  a.displayName && navigate(collectionPath(COLLECTION_AUTHOR, a.displayName))
                }
              >
                <span className="rank-name">{authorDisplayName(a)}</span>
                {/* 副标题第二行「N 个文件」（原型 .a-sub，.a-list .a-sub order:1 让按钮
                    留第一行右侧）；浏览次数不展示（2026-09-05 反馈⑤，旧版无此元素） */}
                <span className="a-sub">{a.fileCount ?? 0} 个文件</span>
                <button
                  type="button"
                  className={`follow-btn${a.followed ? '' : ' follow-btn--idle'}`}
                  disabled={toggleFollow.isPending}
                  onClick={(e) => {
                    // 行本身可点进作者文件页：关注按钮拦截冒泡，避免误触发跳转
                    e.stopPropagation()
                    if (a.id) toggleFollow.mutate({ authorId: a.id, follow: !a.followed })
                  }}
                >
                  {a.followed ? '已关注' : '关注'}
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="a-empty">没有匹配的作者</p>
        )}
      </div>
    </div>
  )
}
