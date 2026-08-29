/**
 * Sparkline 迷你趋势图（纯 SVG 自绘，零第三方依赖——项目禁止引入图表库）。
 *
 * 数据归一化到以最大值为基准的坐标空间；全部采样为 0 时画底部横线（不产生 NaN）。
 * 颜色全部引用 var(--qm-*) 设计 token（web/README 铁律，禁止硬编码色值）。
 */

/** SVG 折线的最大数据点数：超过则按窗口截断（展示层只画最近 N 点，不聚合）。
 * 导出供数据侧（Dashboard 采样保留窗口）引用——窗口与显示上限同源，避免两处 60 漂移。 */
export const SPARKLINE_MAX_POINTS = 60

/** 折线描边色（token；tokens.css 无独立"图表色"，主色即项目强调语义） */
const LINE_COLOR_VAR = 'var(--qm-primary)'

export interface SparklineProps {
  /** 数值序列（时间顺序，最近在末尾）；空数组渲染占位横线 */
  data: number[]
  /** 画布高度（px）；宽度由父容器决定，组件自适应填充 */
  height?: number
  /** 画布宽度（px）；默认 0 = 不指定（配合 CSS 拉伸时用 viewBox 等比缩放） */
  width?: number
  /** 小注文字（如"最近 3 分钟"）；展示在折线下方 */
  label?: string
}

/**
 * 面积填充：同一 path 闭合到底边 + 线性渐变（同源色不同透明度）。
 * 为什么渐变而非纯色填充：面积与折线同色会抢视觉主体，低透明度渐变弱化"图形下方"区域。
 */
export function Sparkline({ data, height = 48, width = 0, label }: SparklineProps) {
  const points = data.slice(-SPARKLINE_MAX_POINTS)
  const count = points.length
  // width<=0 时用内部基准宽（viewBox 逻辑坐标），配合 preserveAspectRatio="none" 由 CSS 横向拉伸
  const viewWidth = width > 0 ? width : 100

  // y 坐标归一化：最大值为 1 → 顶边；空/全 0 → 底边横线（图表语义"平稳无波动"）
  const maxValue = Math.max(...points, 1)
  const stepX = viewWidth / Math.max(count - 1, 1)
  const toY = (value: number) => (count === 0 ? height : height - (value / maxValue) * height)

  const polyPoints = points.map((value, index) => {
    const x = index * stepX
    return `${x},${toY(value)}`
  })
  const linePath = polyPoints.length > 0 ? `M ${polyPoints.join(' L ')}` : ''
  const areaPath =
    polyPoints.length > 0
      ? `${linePath} L ${viewWidth},${height} L 0,${height} Z`
      : ''

  return (
    <div className="flex w-full flex-col gap-1">
      <svg className="h-full w-full" viewBox={`0 0 ${viewWidth} ${height}`} role="img" aria-label={label ?? undefined} preserveAspectRatio="none">
        {/* 面积渐变：stop 颜色来自同一 token，透明度递减做弱化（随主题自动联动） */}
        <defs>
          <linearGradient id="qm-sparkline-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={LINE_COLOR_VAR} stopOpacity="0.25" />
            <stop offset="100%" stopColor={LINE_COLOR_VAR} stopOpacity="0" />
          </linearGradient>
        </defs>
        {areaPath !== '' && <path d={areaPath} fill="url(#qm-sparkline-fill)" />}
        {linePath !== '' && (
          <path
            d={linePath}
            fill="none"
            stroke={LINE_COLOR_VAR}
            strokeWidth={1.5}
            strokeLinejoin="round"
            strokeLinecap="round"
            vectorEffect="non-scaling-stroke"
          />
        )}
      </svg>
      {label && <span className="text-xs text-[var(--qm-text-muted)]">{label}</span>}
    </div>
  )
}
