/**
 * 折线图悬停取数与提示覆盖层（数据页浏览/播放趋势、维护页网络负载共用）。
 *
 * 旧版 LineChartView 的「气泡含系列名」在 web 桌面端的对应物：鼠标悬停时
 * 按光标 x 比例换算最近采样下标，画竖向参考线 + 各系列高亮点 + 数值提示。
 * 本模块只管「鼠标 → (frac, idx)」与覆盖层渲染，几何映射（各图自己的
 * polyline 公式）由调用方提供，避免两套坐标系互相污染（代码卫生约束 6：
 * 第 2 次出现即抽共享——本组件即两图合并结果）。
 */

import { useCallback, useRef, useState } from 'react'

export interface TrendHoverState {
  /** 光标在图宽内的比例（0..1，与折线 x 等宽铺满映射一致） */
  frac: number
  /** 最近的采样下标（round(frac*(count-1))） */
  idx: number
}

/** 悬停取数：count=采样点数；返回 ref 挂图表包裹层（必须 position:relative） */
export function useTrendHover(count: number) {
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const [hover, setHover] = useState<TrendHoverState | null>(null)
  const onMove = useCallback(
    (e: React.MouseEvent) => {
      const rect = wrapRef.current?.getBoundingClientRect()
      if (!rect || rect.width === 0 || count === 0) return
      const frac = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width))
      setHover({ frac, idx: Math.round(frac * (count - 1)) })
    },
    [count],
  )
  const onLeave = useCallback(() => setHover(null), [])
  return { wrapRef, hover, onMove, onLeave }
}

export interface TrendHoverSeriesPoint {
  /** 该系列在悬停 x 处的 y（0..1，相对图高比例，调用方用自己的归一化公式算） */
  yFrac: number
  /** 系列色（CSS 变量，与折线 stroke 同源） */
  color: string
}

/**
 * 悬停覆盖层：竖向参考线 + 各系列高亮点 + 数值提示（HTML 绝对定位——
 * SVG 是 preserveAspectRatio=none 拉伸，圆点/文字放内部会变形，故全走 HTML）。
 * 提示框在靠边时水平翻转，避免溢出图外。
 */
export function TrendHoverOverlay({
  frac,
  items,
  tip,
}: {
  frac: number
  items: TrendHoverSeriesPoint[]
  tip: React.ReactNode
}) {
  const transform =
    frac > 0.8 ? 'translate(-100%, 0)' : frac < 0.2 ? 'translate(0, 0)' : 'translate(-50%, 0)'
  return (
    <>
      <div className="trend-guide" style={{ left: `${frac * 100}%` }} />
      {items.map((p, i) => (
        <span
          key={i}
          className="trend-dot"
          style={{ left: `${frac * 100}%`, top: `${p.yFrac * 100}%`, background: p.color }}
        />
      ))}
      <div className="trend-tip" style={{ left: `${frac * 100}%`, transform }}>
        {tip}
      </div>
    </>
  )
}
