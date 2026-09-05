/**
 * 浏览/播放趋势折线（数据页）。recharts 实现（选型 ADR-0016）：悬停提示、
 * 参考线、高亮点均为库内置能力（Tooltip + activeDot + cursor），替代此前
 * 手搓 HTML 覆盖层（与曲线对不齐，2026-09-05 用户拍板换库）。
 * 纯渲染组件：输入分桶数组，不触网（铁律 7）。颜色走 CSS 变量，与图例同源。
 */

import {
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { TrendBucket } from '@/api/generated'

/** Tooltip / cursor 共用样式（token 化，与卡片视觉一致） */
const TIP_STYLE = {
  contentStyle: {
    background: 'var(--pop-chip-hover-bg)',
    border: 'none',
    borderRadius: 6,
    fontSize: 11,
    color: 'var(--text-main)',
    padding: '4px 9px',
  },
  labelStyle: { color: 'var(--text-sub)', marginRight: 2 },
  itemStyle: { padding: 0 },
  cursor: { stroke: 'var(--text-sub)', strokeDasharray: '4 4', strokeOpacity: 0.4 },
} as const

export function TrendChart({ buckets }: { buckets: TrendBucket[] }) {
  if (!buckets.length) return <p className="rank-note">暂无数据</p>
  const data = buckets.map((b) => ({
    label: b.label ?? '',
    浏览: b.viewCount ?? 0,
    播放: b.playCount ?? 0,
  }))

  return (
    <>
      <div className="trend-legend">
        <span>
          <i style={{ background: 'var(--qm-primary)' }} />浏览
        </span>
        <span>
          <i style={{ background: 'var(--trend-line-sub)' }} />播放
        </span>
      </div>
      <ResponsiveContainer width="100%" height={210}>
        <LineChart data={data} margin={{ top: 6, right: 8, left: 8, bottom: 0 }}>
          <XAxis
            dataKey="label"
            tick={{ fontSize: 10, fill: 'var(--text-sub)' }}
            tickLine={false}
            axisLine={false}
            interval="preserveStartEnd"
            minTickGap={40}
          />
          <YAxis hide />
          <Tooltip {...TIP_STYLE} />
          <Line
            type="monotone"
            dataKey="浏览"
            name="浏览"
            stroke="var(--qm-primary)"
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 4, fill: 'var(--qm-primary)' }}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="播放"
            name="播放"
            stroke="var(--trend-line-sub)"
            strokeWidth={2}
            strokeDasharray="4 4"
            dot={false}
            activeDot={{ r: 4, fill: 'var(--trend-line-sub)' }}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </>
  )
}
