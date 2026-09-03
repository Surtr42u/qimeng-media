/**
 * 内容榜网格（数据页 Top5 / 完整榜单页共用）。纯渲染组件：输入 AssetSummary[]，
 * rank-item 结构照原型（封面 + 右下数值角标 + 标题），点击由调用方注入跳转。
 */

import type { AssetSummary } from '@/api/generated'
import { formatCount } from '@/lib/format'

export function ContentRankGrid({
  items,
  onOpen,
}: {
  items: AssetSummary[]
  onOpen: (a: AssetSummary) => void
}) {
  if (!items.length) return <p className="rank-note">暂无数据</p>
  return (
    <div className="rank-cards">
      {items.map((a) => (
        <div className="rank-item" key={a.id} onClick={() => onOpen(a)} role="button">
          <div className="rank-cover">
            <img src={a.thumbUrl ?? ''} alt="" loading="lazy" />
            <span className="rank-views">{formatCount(a.viewCount)}</span>
          </div>
          <p className="rank-title">{a.fileName ?? ''}</p>
        </div>
      ))}
    </div>
  )
}
