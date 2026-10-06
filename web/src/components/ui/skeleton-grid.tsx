/**
 * 极光骨架屏网格组件（SkeletonGrid & SkeletonCard）
 *
 * 解决数据加载中（pending / loading）时的首屏空白与布局跳闪（CLS）。
 * 几何尺寸、16:9 比例、圆角、间距完全对齐 MediaCard 与 .grid / .media-grid。
 * 纯 CSS 实现 Shimmer 极光流光扫光，零额外 JS 依赖，明暗主题自适应。
 */

interface SkeletonCardProps {
  className?: string
}

function SkeletonCard({ className = '' }: SkeletonCardProps) {
  return (
    <div className={`skeleton-card ${className}`} aria-hidden="true">
      <div className="skeleton-card--cover skeleton-shimmer" />
      <div className="skeleton-card--title skeleton-shimmer" />
      <div className="skeleton-card--meta skeleton-shimmer" />
    </div>
  )
}

interface SkeletonGridProps {
  /** 骨架卡片数量，缺省 12 张（刚好覆盖典型桌面全屏 2~3 行） */
  count?: number
  /** 网格容器类名，支持 .grid 或 .media-grid */
  className?: string
}

export function SkeletonGrid({ count = 12, className = 'grid' }: SkeletonGridProps) {
  return (
    <div className={`skeleton-grid ${className}`} aria-busy="true" aria-label="加载中...">
      {Array.from({ length: count }, (_, i) => (
        <SkeletonCard key={i} />
      ))}
    </div>
  )
}
