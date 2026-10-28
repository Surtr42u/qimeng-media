/**
 * 行式榜单列表（标签榜/作者榜，数据页与完整榜单页共用）。纯渲染组件：
 * 结构照原型 ul > li（.rank-name + b 计数），点击行由调用方注入跳转集合子页。
 * count 为空串不渲染计数徽标；作者总览只显示姓名，保持三张榜单的单行密度。
 */

import { EmptyNote } from './EmptyNote'

export interface RankRow {
  name: string
  count: string
}

export function RankRowList({
  rows,
  onSelect,
}: {
  rows: RankRow[]
  onSelect?: (name: string) => void
}) {
  if (!rows.length) return <EmptyNote />
  return (
    <ul>
      {rows.map((r) => (
        <li key={r.name} role={onSelect ? 'button' : undefined} tabIndex={onSelect ? 0 : undefined} onClick={onSelect ? () => onSelect(r.name) : undefined} onKeyDown={event => { if (onSelect && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); onSelect(r.name) } }}>
          <span className="rank-name">{r.name}</span>
          {r.count ? <b>{r.count}</b> : null}
        </li>
      ))}
    </ul>
  )
}
