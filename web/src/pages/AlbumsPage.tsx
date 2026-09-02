import { Fragment, useMemo, useState } from 'react'
import { MediaCard } from '@/components/media/MediaCard'
import { LOCALE_ZH } from '@/lib/constants'
import { ALBUM_DIMS, ALBUM_DIM_ORDER, MOCK_ALBUM_FILES, type AlbumDimKey } from '@/pages/mock'

/**
 * 相册页（原型 #page-albums 移植）：两段式胶囊筛选 + 内容网格。
 * 交互照抄原型 app.js albumState 状态机：切维度重置值/展开态，点值即时过滤，
 * 排序中「最新」为倒序、「按名称」为中文升序，其余保持原序。
 */

interface AlbumState {
  dim: AlbumDimKey
  value: string
  sort: string
  expanded: boolean
}

const INITIAL_STATE: AlbumState = { dim: 'partition', value: '全部', sort: '精选', expanded: false }

const SORT_OPTIONS = ['精选', '最新', '最旧', '按名称']

export default function AlbumsPage() {
  const [state, setState] = useState<AlbumState>(INITIAL_STATE)

  const dimValues = ALBUM_DIMS[state.dim].values

  const files = useMemo(() => {
    const filtered =
      state.value === '全部'
        ? [...MOCK_ALBUM_FILES]
        : MOCK_ALBUM_FILES.filter((f) => f.tags[state.dim] === state.value)
    if (state.sort === '最新') return filtered.reverse()
    if (state.sort === '按名称')
      return filtered.sort((a, b) => a.name.localeCompare(b.name, LOCALE_ZH))
    return filtered
  }, [state])

  return (
    <div className="page" id="page-albums">
      <section className="filter-card">
        <div className="pill-row" id="albumDims" role="group" aria-label="筛选维度">
          {ALBUM_DIM_ORDER.map((key) => {
            const dim = ALBUM_DIMS[key]
            return (
              <Fragment key={key}>
                {/* 分隔线固定插在「类型」前（与原型渲染顺序一致） */}
                {key === 'type' && <span className="pill-divider" aria-hidden="true" />}
                <button
                  className={state.dim === key ? 'pill active' : 'pill'}
                  type="button"
                  onClick={() => setState({ dim: key, value: '全部', sort: state.sort, expanded: false })}
                >
                  {dim.label} <span className="pill-count">{dim.values.length - 1}</span>
                </button>
              </Fragment>
            )
          })}
        </div>
        <div className={state.expanded ? 'pill-row value-row expanded' : 'pill-row value-row'} id="albumValues">
          {dimValues.map(([name, count]) => (
            <button
              key={name}
              className={state.value === name ? 'pill active' : 'pill'}
              type="button"
              onClick={() => setState({ ...state, value: name })}
            >
              {name} <span className="pill-count">{count.toLocaleString('zh-CN')}</span>
            </button>
          ))}
        </div>
        <button
          className="expand-btn"
          id="albumValuesToggle"
          type="button"
          hidden={dimValues.length <= 9}
          onClick={() => setState({ ...state, expanded: !state.expanded })}
        >
          {state.expanded ? '收起 ⌃' : '展开 ⌄'}
        </button>
        <div className="pill-row sort-row" id="albumSort">
          <span className="sort-label">排序</span>
          {SORT_OPTIONS.map((opt) => (
            <button
              key={opt}
              className={state.sort === opt ? 'pill active' : 'pill'}
              type="button"
              onClick={() => setState({ ...state, sort: opt })}
            >
              {opt}
            </button>
          ))}
        </div>
      </section>
      <div className="media-grid" id="albumGrid">
        {files.length > 0 ? (
          files.map((f) => (
            <MediaCard key={f.name} cover={f.cover} title={f.name} duration={f.duration} up={f.up} date={f.date} />
          ))
        ) : (
          <p className="grid-empty">该筛选组合下暂无内容，换个胶囊试试。</p>
        )}
      </div>
    </div>
  )
}
