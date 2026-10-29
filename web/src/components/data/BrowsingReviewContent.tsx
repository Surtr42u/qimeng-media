import { useState } from 'react'
import type { BrowsingReview } from '@/api/generated'
import { LOCALE_ZH } from '@/lib/constants'

export function ReviewMetrics({ review }: { review: BrowsingReview }) {
  const metrics: [string, number, string][] = [
    ['首次浏览', review.firstViews, '次'], ['再次浏览', review.repeatViews, '次'],
    ['浏览内容', review.uniqueFiles, '个'], ['活跃天数', review.activeDays, '天'],
  ]
  return <dl className="review-metrics">{metrics.map(([label, value, unit]) => <div key={label}><dt>{label}</dt><dd>{value.toLocaleString(LOCALE_ZH)}<small>{unit}</small></dd></div>)}</dl>
}

/** 只呈现服务端口径；百分比变化在服务端计算，柱高仅作显示缩放。 */
export function BrowsingReviewContent({ review }: { review: BrowsingReview }) {
  const [selectedHour, setSelectedHour] = useState(0)
  const peak = Math.max(1, ...review.hours.map(hour => hour.views))
  return <div className="review-sections">
    <section className="chart-card" aria-label="探索与回访">
      <div className="workspace-card-head"><h3>探索与回访</h3><span>{review.range === 'all' ? '全部已记录浏览' : `${review.fromDay} — ${review.toDay}`}</span></div>
      <div className="review-window-totals"><span>本期浏览 <strong>{review.viewCount.toLocaleString(LOCALE_ZH)}</strong> 次</span>{review.range !== 'all' && <span>上期浏览 <strong>{review.previousViewCount.toLocaleString(LOCALE_ZH)}</strong> 次</span>}</div>
      <ReviewMetrics review={review} />
      <p className="rank-note">{review.viewCount === 0 ? '这个时段还没有浏览记录' : '每个内容的首条浏览记为首次，其后的打开记为再次浏览。'}</p>
    </section>
    <section className="chart-card" aria-label="兴趣变化">
      <div className="workspace-card-head"><h3>兴趣变化</h3><span>与前一等长时段比较</span></div>
      {review.range === 'all' ? <p className="workspace-empty-note">选择近7天、近30天或近90天，查看与前一时段的变化。</p>
        : !review.comparisonAvailable ? <p className="workspace-empty-note">前后两个时段都有浏览记录后，这里会显示兴趣变化。</p>
          : review.interests.length === 0 ? <p className="workspace-empty-note">与前一时段相比，兴趣份额没有变化。</p>
            : <><p className="rank-note">对比 {review.previousFromDay} — {review.previousToDay} · 浏览份额变化最大的作者与标签</p>
              <ul className="review-interests">{review.interests.map(item => <li key={`${item.kind}:${item.id}`}>
                <div className="review-interest-head"><div><small className="review-kind">{item.kind === 'author' ? '作者' : '标签'}</small><strong>{item.name}</strong></div><span className={item.changePoints > 0 ? 'review-change rising' : 'review-change falling'}>{item.changePoints > 0 ? '↗ 份额上升' : '↘ 份额下降'} <b>{Math.abs(item.changePoints).toFixed(1)}</b> 个百分点</span></div>
                <div className="review-share-comparison">{[
                  { label: '上期', share: item.previousShare, views: item.previousViews, tone: 'previous' },
                  { label: '本期', share: item.currentShare, views: item.currentViews, tone: 'current' },
                ].map(period => <div className={`review-share-row ${period.tone}`} key={period.label}><span>{period.label}</span><div className="review-share-track" aria-hidden="true"><i style={{ width: `${period.share}%` }} /></div><b>{period.share.toFixed(1)}%</b><small>{period.views.toLocaleString(LOCALE_ZH)} 次浏览</small></div>)}</div>
              </li>)}</ul><p className="rank-note">份额按关联内容的浏览次数占本时段全部浏览计算；同一内容可关联多个作者或标签。</p></>}
    </section>
    <section className="chart-card" aria-label="活跃时段">
      <div className="workspace-card-head"><h3>活跃时段</h3><span>按服务端本地时间</span></div>
      <div className="review-hour-chart"><div className="review-hour-scale" aria-hidden="true"><span>{peak} 次</span><span>0</span></div><div className="review-hour-plot">
        <div className="review-hours" role="list" aria-label="24小时浏览次数">{review.hours.map(hour => <div key={hour.hour} role="listitem" aria-label={`${hour.hour}时，${hour.views}次`} title={`${hour.hour}时 · ${hour.views}次`} className={selectedHour === hour.hour ? 'selected' : ''} onMouseEnter={() => setSelectedHour(hour.hour)}><i style={{ height: `${hour.views / peak * 100}%` }} /></div>)}</div>
        <div className="review-hour-labels" aria-hidden="true">{review.hours.map(hour => <span key={hour.hour}>{[0, 6, 12, 18, 23].includes(hour.hour) ? hour.hour : ''}</span>)}</div>
      </div></div>
      <div className="review-hour-detail" role="status"><strong>{String(selectedHour).padStart(2, '0')}:00–{String(selectedHour + 1).padStart(2, '0')}:00</strong><span>{review.hours[selectedHour].views.toLocaleString(LOCALE_ZH)} 次浏览</span></div>
      <label className="review-hour-picker">查看小时<input aria-label="查看小时" type="range" min="0" max="23" step="1" value={selectedHour} onChange={event => setSelectedHour(Number(event.target.value))} /></label>
      <p className="rank-note">汇总已记录的浏览次数；导入历史按保存的记录时间统计。</p>
    </section>
  </div>
}
