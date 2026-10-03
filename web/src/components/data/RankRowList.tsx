/**
 * 行式榜单列表（标签榜/作者榜，数据页与完整榜单页共用）。纯渲染组件：
 * 结构照原型 ul > li（.rank-name + b 计数），点击行由调用方注入跳转集合子页。
 * 可选 sub 副标题（第二行，如作者总览「N 个文件 · 浏览 M 次」，原型 .rank-sub2）；
 * count 为空串不渲染计数徽标（作者总览行无计数，对照原型 renderAuthorOverview）。
 */

import { EmptyNote } from './EmptyNote'

export interface RankRow {
  name: string
  count: string
  /** 可选副标题（独占第二行；无副标题的既有行不传，渲染不变） */
  sub?: string
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
        <li key={r.name} onClick={onSelect ? () => onSelect(r.name) : undefined}>
          <span className="rank-name">{r.name}</span>
          {r.count ? <b>{r.count}</b> : null}
          {r.sub ? <span className="rank-sub2">{r.sub}</span> : null}
        </li>
      ))}
    </ul>
  )
}
