import { useId, useState } from 'react'
import type { BrowsingReview } from '@/api/generated'

export function ReviewInterestCard({ item }: { item: BrowsingReview['interests'][number] }) {
  const [expanded, setExpanded] = useState(false)
  const detailId = useId()
  const rising = item.changePoints > 0
  return <li className="review-interest-card">
    <button type="button" className="review-interest-trigger" aria-expanded={expanded} aria-controls={detailId}
      aria-label={`${item.name}，${expanded ? '收起' : '查看'}详情`} onClick={() => setExpanded(value => !value)}>
      <span className="review-interest-copy">
        <span className="review-interest-name"><strong>{item.name}</strong><small>{item.kind === 'author' ? '作者' : '标签'}</small></span>
        <span className="review-interest-caption">在浏览中的份额{rising ? '增加' : '减少'}</span>
      </span>
      <span className="review-interest-action" aria-hidden="true">{expanded ? '收起' : '详情'}</span>
    </button>
    <div className="review-interest-detail" id={detailId} hidden={!expanded}>
      <dl className="review-interest-values">
        <div><dt>上期份额</dt><dd>{item.previousShare.toFixed(1)}<small>%</small><span>{item.previousViews} 次浏览</span></dd></div>
        <div><dt>本期份额</dt><dd>{item.currentShare.toFixed(1)}<small>%</small><span>{item.currentViews} 次浏览</span></dd></div>
      </dl>
      <p>份额{rising ? '上升' : '下降'} {Math.abs(item.changePoints).toFixed(1)} 个百分点</p>
    </div>
  </li>
}
