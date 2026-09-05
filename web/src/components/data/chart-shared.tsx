/**
 * recharts 图表共用件（ADR-0016 换库后收拢，此前 TrendChart 与维护页网络负载图
 * 各内联一份逐字相同的 Tooltip 样式与图例 JSX）。纯常量/纯渲染组件，不触网（铁律 7）。
 */

/** Tooltip / cursor 共用样式（token 化，与卡片视觉一致）。
 *  网络负载图无 XAxis label，labelStyle 多传无害——两图共用这一份。 */
export const TIP_STYLE = {
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

/** 图例（.trend-legend 结构：色块 i + 文案；颜色传曲线同款 CSS 变量保持同源） */
export function TrendLegend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <div className="trend-legend">
      {items.map((it) => (
        <span key={it.label}>
          <i style={{ background: it.color }} />
          {it.label}
        </span>
      ))}
    </div>
  )
}
