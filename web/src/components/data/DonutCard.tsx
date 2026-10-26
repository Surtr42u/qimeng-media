/**
 * 浏览量占比环形卡。纯渲染组件：slices 驱动扇区与图例，可展示中心总数，不触网。
 * 结构照原型（.donut svg + .legend；扇区/圆点类名 arc-1/arc-2/d1/d2 等照旧，
 * 颜色来自 styles/glass.css）；扇区弧长按百分比动态计算——
 * 原型是写死 dasharray，接真实数据后必须动态才与图例一致。
 */

import { LoadingHint } from '@/components/ui/loading-hint'

export interface DonutSlice {
  /** 图例圆点类（d1/d2/d4…，颜色见 glass.css .legend .dN） */
  dot: string
  /** 扇区圆类（arc-1/arc-2…，颜色见 glass.css .donut .arc-N） */
  arc: string
  name: string
  value: string
  /** 占比（0~100，扇区弧长度量） */
  pct: number
}

/** 扇区环周长：r=44 → 2πr≈276.5，与原型 CSS stroke-dasharray 分母 277 一致 */
const RING_CIRCUMFERENCE = 277

export function DonutCard({
  title,
  sub,
  slices,
  loading = false,
  emptyText = '暂无数据',
  centerValue,
  centerLabel,
}: {
  title: string
  sub: string
  slices: DonutSlice[]
  loading?: boolean
  emptyText?: string
  centerValue?: string
  centerLabel?: string
}) {
  if (loading) {
    return (
      <div className="donut-card">
        <h3>{title}</h3>
        <p>{sub}</p>
        <LoadingHint className="m-note" />
      </div>
    )
  }
  if (!slices.length) {
    return (
      <div className="donut-card">
        <h3>{title}</h3>
        <p>{sub}</p>
        <p className="m-note">{emptyText}</p>
      </div>
    )
  }

  // 逐个累计偏移：每个扇区是覆盖在环上的独立圆（stroke-dashoffset 旋转起点）。
  // 用前缀和而非累加变量（React 编译器不可变性规则：渲染期禁止 `let` 再赋值）。
  const arcs = slices.map((s, i) => {
    const len = Math.max(0, (s.pct / 100) * RING_CIRCUMFERENCE)
    const offset = slices
      .slice(0, i)
      .reduce((acc, prev) => acc + Math.max(0, (prev.pct / 100) * RING_CIRCUMFERENCE), 0)
    return { ...s, len, offset }
  })

  return (
    <div className="donut-card">
      <h3>{title}</h3>
      <p>{sub}</p>
      <div className="donut-row">
        <div className="donut-visual"><svg className="donut" viewBox="0 0 120 120" aria-hidden="true">
          <circle cx="60" cy="60" r="44" className="ring" />
          {arcs.filter(slice => slice.pct > 0).map((s) => (
            <circle
              key={s.name}
              cx="60"
              cy="60"
              r="44"
              className={`arc ${s.arc}`}
              strokeDasharray={`${s.len.toFixed(1)} ${(RING_CIRCUMFERENCE - s.len).toFixed(1)}`}
              strokeDashoffset={-s.offset}
            />
          ))}
        </svg>{centerValue !== undefined && <div className="donut-center"><strong>{centerValue}</strong><small>{centerLabel}</small></div>}</div>
        <ul className="legend">
          {arcs.map((s) => (
            <li key={s.name}>
              <i className={`dot ${s.dot}`} />
              {s.name} <b>{s.value}</b> <span>{s.pct.toFixed(1)}%</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
