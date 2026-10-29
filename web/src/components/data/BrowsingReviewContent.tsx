import { useState } from 'react'
import type { BrowsingReview } from '@/api/generated'
import { LOCALE_ZH } from '@/lib/constants'
import { ReviewInterestCard } from './ReviewInterestCard'

export function ReviewMetrics({ review }: { review: BrowsingReview }) {
  const metrics: [string, number, string][] = [
    ['首次浏览', review.firstViews, '次'], ['再次浏览', review.repeatViews, '次'],
    ['浏览内容', review.uniqueFiles, '个'], ['活跃天数', review.activeDays, '天'],
  ]
  return <dl className="review-metrics">{metrics.map(([label, value, unit]) => <div key={label}><dt>{label}</dt><dd>{value.toLocaleString(LOCALE_ZH)}<small>{unit}</small></dd></div>)}</dl>
}

function Duration({ seconds }: { seconds: number }) {
  const hours = Math.floor(seconds / 3600), minutes = Math.floor(seconds % 3600 / 60)
  return <>{hours > 0 && <><b>{hours}</b><small>小时</small></>}{seconds >= 60 ? <><b>{minutes}</b><small>分钟</small></> : <><b>{seconds}</b><small>秒</small></>}</>
}

/** 只呈现服务端口径；柱高仅作显示缩放。 */
export function BrowsingReviewContent({ review }: { review: BrowsingReview }) {
  const [selectedHour, setSelectedHour] = useState<number | null>(null)
  const peak = Math.max(1, ...review.hours.map(hour => hour.seconds))
  const categories = [
    { key: 'imageSeconds', label: '图片', tone: 'image' },
    { key: 'videoSeconds', label: '视频', tone: 'video' },
    { key: 'animatedSeconds', label: '动图', tone: 'animated' },
  ] as const
  return <div className="review-sections">
    <section className="chart-card review-activity" aria-label="活跃时段">
      <div className="review-activity-head"><h3>{review.range === 'all' ? '全部' : review.range === 'day' ? '近30天' : review.range === '90d' ? '近90天' : '近7天'}</h3><div className="review-activity-total" aria-label="浏览停留时长"><Duration seconds={review.seconds} /></div></div>
      <div className="review-hour-plot">
        <div className="review-hour-labels" aria-hidden="true">{review.hours.map(hour => <span key={hour.hour}>{[0, 6, 12, 18].includes(hour.hour) ? `${String(hour.hour).padStart(2, '0')}时` : ''}</span>)}</div>
        <div className="review-hours" role="group" aria-label="24小时浏览时长">{review.hours.map(hour => <button key={hour.hour} type="button" aria-label={`${hour.hour}时，${hour.seconds}秒，${hour.views}次浏览`} aria-pressed={selectedHour === hour.hour} tabIndex={hour.hour === (selectedHour ?? 0) ? 0 : -1}
          onMouseEnter={() => setSelectedHour(hour.hour)} onFocus={() => setSelectedHour(hour.hour)} onClick={() => setSelectedHour(hour.hour)}
          onKeyDown={event => {
            const next = event.key === 'Home' ? 0 : event.key === 'End' ? 23 : event.key === 'ArrowRight' ? Math.min(23, hour.hour + 1) : event.key === 'ArrowLeft' ? Math.max(0, hour.hour - 1) : null
            if (next !== null) { event.preventDefault(); setSelectedHour(next); (event.currentTarget.parentElement!.children[next] as HTMLButtonElement).focus() }
          }}><span className="review-hour-stack" style={{ height: `${hour.seconds / peak * 100}%` }}>{categories.map(category => <i className={category.tone} key={category.key} style={{ height: `${hour.seconds ? hour[category.key] / hour.seconds * 100 : 0}%` }} />)}</span></button>)}</div>
      </div>
      <div className="review-activity-legend">{categories.map(category => <div className={category.tone} key={category.key}><strong>{category.label}</strong><span><Duration seconds={review[category.key]} /></span></div>)}</div>
      <div className="review-hour-detail" role="status">{selectedHour === null ? '点击柱形查看小时详情' : <><strong>{String(selectedHour).padStart(2, '0')}:00–{String(selectedHour + 1).padStart(2, '0')}:00</strong><span><Duration seconds={review.hours[selectedHour].seconds} /> · {review.hours[selectedHour].views} 次浏览</span></>}</div>
      <p className="rank-note">按服务端本地时间 · 统计已记录的详情停留，视频不等同播放时长</p>
    </section>
    <section className="chart-card" aria-label="探索与回访">
      <div className="workspace-card-head"><h3>探索与回访</h3><span>{review.range === 'all' ? '全部已记录浏览' : `${review.fromDay} — ${review.toDay}`}</span></div>
      <div className="review-window-totals"><span>本期浏览 <strong>{review.viewCount.toLocaleString(LOCALE_ZH)}</strong> 次</span>{review.range !== 'all' && <span>上期浏览 <strong>{review.previousViewCount.toLocaleString(LOCALE_ZH)}</strong> 次</span>}</div>
      <ReviewMetrics review={review} />
      <p className="rank-note">{review.viewCount === 0 ? '这个时段还没有浏览记录' : '每个内容的首条浏览记为首次，其后的打开记为再次浏览。'}</p>
    </section>
    <section className="chart-card" aria-label="兴趣变化">
      <div className="workspace-card-head"><h3>兴趣变化</h3><span>与上期相比</span></div>
      {review.range === 'all' ? <p className="workspace-empty-note">选择近7天、近30天或近90天，查看与前一时段的变化。</p>
        : !review.comparisonAvailable ? <p className="workspace-empty-note">前后两个时段都有浏览记录后，这里会显示兴趣变化。</p>
          : review.interests.length === 0 ? <p className="workspace-empty-note">与前一时段相比，兴趣份额没有变化。</p>
            : <><p className="review-interest-comparison">对比 {review.previousFromDay} — {review.previousToDay}</p>
              <div className="review-interest-groups">{[true, false].map(rising => {
                const items = review.interests.filter(item => (item.changePoints > 0) === rising)
                return items.length > 0 && <section className="review-interest-group" key={String(rising)} aria-label={rising ? '更多关注' : '较少关注'}>
                  <h4>{rising ? '更多关注' : '较少关注'}</h4>
                  <ul className="review-interests">{items.map(item => <ReviewInterestCard key={`${review.range}:${item.kind}:${item.id}`} item={item} />)}</ul>
                </section>
              })}</div>
              <p className="rank-note">点击卡片查看详情 · 比较前一等长时段的浏览份额；作者与标签可多关联。</p></>}

    </section>
  </div>
}
