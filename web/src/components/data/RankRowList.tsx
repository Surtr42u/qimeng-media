/**
 * 行式榜单列表（标签榜/作者榜，数据页与完整榜单页共用）。纯渲染组件：
 * 结构照原型 ul > li（.rank-name + b 计数），点击行由调用方注入跳转集合子页。
 */

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
  if (!rows.length) return <p className="rank-note">暂无数据</p>
  return (
    <ul>
      {rows.map((r) => (
        <li key={r.name} onClick={onSelect ? () => onSelect(r.name) : undefined}>
          <span className="rank-name">{r.name}</span>
          <b>{r.count}</b>
        </li>
      ))}
    </ul>
  )
}
