import { useMemo, useState } from 'react'
import { SearchIcon } from '@/components/shell/icons'
import { MOCK_AUTHORS, type MockAuthor } from '@/pages/mock'

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

// 照 app.js A_SORTERS：默认=数组原序（作者榜热度序）、浏览数降序、作品数降序
const A_SORTERS: Record<AuthorSort, (a: MockAuthor, b: MockAuthor) => number> = {
  default: () => 0,
  browse: (a, b) => b.browse - a.browse,
  works: (a, b) => b.works - a.works,
}

/**
 * 作者管理页（原型 #page-authors 移植）：体系胶囊 + 名字搜索 + 排序三项 +
 * 关注按钮内存态 toggle；关注状态复制进 state，不改 mock 源数组。
 */
export default function AuthorsPage() {
  const [authors, setAuthors] = useState<MockAuthor[]>(() => MOCK_AUTHORS.map((a) => ({ ...a })))
  const [zone, setZone] = useState<AuthorZone>('全部')
  const [keyword, setKeyword] = useState('')
  const [sort, setSort] = useState<AuthorSort>('default')

  // 先 filter 后 sort，slice 避免原地排序污染 filter 结果顺序基准（照 app.js renderAuthors）
  const rows = useMemo(() => {
    const kw = keyword.trim()
    return authors
      .filter((a) => (zone === '全部' || a.type === zone) && (!kw || a.name.includes(kw)))
      .slice()
      .sort(A_SORTERS[sort])
  }, [authors, zone, keyword, sort])

  const toggleFollow = (name: string) =>
    setAuthors((list) => list.map((a) => (a.name === name ? { ...a, followed: !a.followed } : a)))

  return (
    <div className="page" id="page-authors">
      <div className="page-head">
        <h2>作者管理</h2>
        <p>全部作者 · mock 数据</p>
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
        {rows.length ? (
          <ul>
            {rows.map((a) => (
              <li key={a.name}>
                <span className="rank-name">{a.name}</span>
                <button
                  type="button"
                  className={`follow-btn${a.followed ? '' : ' follow-btn--idle'}`}
                  onClick={() => toggleFollow(a.name)}
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
