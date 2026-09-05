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
import { TIP_STYLE, TrendLegend } from './chart-shared'

export function TrendChart({ buckets }: { buckets: TrendBucket[] }) {
  if (!buckets.length) return <p className="rank-note">暂无数据</p>
  const data = buckets.map((b) => ({
    label: b.label ?? '',
    浏览: b.viewCount ?? 0,
    播放: b.playCount ?? 0,
  }))

  return (
    <>
      <TrendLegend
        items={[
          { label: '浏览', color: 'var(--qm-primary)' },
          { label: '播放', color: 'var(--trend-line-sub)' },
        ]}
      />
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
