/**
 * 浏览/播放趋势折线（数据页）。纯渲染组件：输入分桶数组，不触网（铁律 7）。
 * 结构照原型：viewBox 0 0 600 200 + line-a（浏览）/line-b（播放）两条折线，
 * 数据 = 分桶 viewCount/playCount；x 轴 label 取分桶 label 在 SVG 外渲染
 * （SVG 为 preserveAspectRatio=none 拉伸，文字放内部会被压扁；外部 div 用
 * space-between 与折线 x 映射同比例，桶多时均匀采样最多 LABEL_MAX 个防重叠）。
 * 悬停反馈（2026-09-05 用户拍板）：竖向参考线 + 系列高亮点 + 数值提示，
 * 对齐旧版 LineChartView「气泡含系列名」——几何与悬停逻辑见 trend-hover.tsx。
 */

import type { TrendBucket } from '@/api/generated'
import { TrendHoverOverlay, useTrendHover } from './trend-hover'

/** 画布尺寸（viewBox 坐标系，与原型一致） */
const CHART_W = 600
const CHART_H = 200
/** 折线上下留白（垂直方向，viewBox 单位） */
const PAD_Y = 20
/** x 轴 label 最多显示个数（近 90 天/近 24 月等长窗口下防重叠） */
const LABEL_MAX = 6

/** 单点 y（viewBox 坐标）：按 max 归一化留上下留白——与 toPoints 同公式 */
function yAt(v: number, max: number): number {
  return max > 0 ? PAD_Y + (1 - v / max) * (CHART_H - PAD_Y * 2) : CHART_H - PAD_Y
}

/** 分桶值序列 → polyline points（x 等宽铺满，y 按 max 归一化留上下留白） */
function toPoints(values: number[], max: number): string {
  const n = values.length
  return values
    .map((v, i) => {
      const x = n === 1 ? CHART_W / 2 : (i / (n - 1)) * CHART_W
      return `${x.toFixed(1)},${yAt(v, max).toFixed(1)}`
    })
    .join(' ')
}

export function TrendChart({ buckets }: { buckets: TrendBucket[] }) {
  const views = buckets.map((b) => b.viewCount ?? 0)
  const plays = buckets.map((b) => b.playCount ?? 0)
  const max = Math.max(...views, ...plays, 0)
  const n = buckets.length
  const { wrapRef, hover, onMove, onLeave } = useTrendHover(n)

  // 采样下标：桶少全显，桶多取 4 个均匀点（首/1/3/末），位置与折线 x 映射一致
  const labelIdx =
    n <= LABEL_MAX
      ? buckets.map((_, i) => i)
      : [0, Math.round((n - 1) / 3), Math.round((2 * (n - 1)) / 3), n - 1]

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
      <div className="trend-wrap" ref={wrapRef} onMouseMove={onMove} onMouseLeave={onLeave}>
        <svg
          className="trend-svg"
          viewBox={`0 0 ${CHART_W} ${CHART_H}`}
          preserveAspectRatio="none"
          aria-label="浏览与播放趋势"
        >
          <polyline points={toPoints(views, max)} className="line-a" />
          <polyline points={toPoints(plays, max)} className="line-b" />
        </svg>
        {hover && buckets[hover.idx] ? (
          <TrendHoverOverlay
            frac={hover.frac}
            items={[
              { yFrac: yAt(views[hover.idx], max) / CHART_H, color: 'var(--qm-primary)' },
              { yFrac: yAt(plays[hover.idx], max) / CHART_H, color: 'var(--trend-line-sub)' },
            ]}
            tip={
              <>
                <span className="trend-tip-label">{buckets[hover.idx].label}</span>
                <span className="trend-tip-series">
                  <i style={{ background: 'var(--qm-primary)' }} />
                  {views[hover.idx]}
                </span>
                <span className="trend-tip-series">
                  <i style={{ background: 'var(--trend-line-sub)' }} />
                  {plays[hover.idx]}
                </span>
              </>
            }
          />
        ) : null}
      </div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          marginTop: 6,
          fontSize: 10,
          color: 'var(--text-sub)',
        }}
      >
        {labelIdx.map((i) => (
          <span key={i}>{buckets[i]?.label ?? ''}</span>
        ))}
      </div>
    </>
  )
}
