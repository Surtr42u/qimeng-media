/**
 * 无限滚动哨兵（推荐页/全部页共用，第 2 次复用即提取——代码卫生约束）。
 *
 * 纯布局组件：只观察可见性并通知回调，数据拉取由父级 hook 负责。
 * rootMargin '600px'（≈ 两屏高/30 项）：滚动到底部前就预取下一页，移动端不出现"触底转圈"
 * ——交互规格建议值（距底部 ≤6 项语义常量化注释）。
 */

import { useEffect, useRef } from 'react'
import { Skeleton } from '@/components/ui/skeleton'

/** 预取触发阈值：距滚动容器底部 ≤600px（交互规格：rootMargin 建议 '600px'） */
const PREFETCH_MARGIN = '600px'

/** 加载更多时的占位骨架数量（网格一行约 2~5 项，取一行骨架） */
const SKELETON_COUNT = 2

interface InfiniteScrollSentinelProps {
  hasNextPage: boolean
  /** 正在加载下一页（isFetchingNextPage）——显示骨架 */
  isLoading: boolean
  onLoadMore: () => void
}

export function InfiniteScrollSentinel({ hasNextPage, isLoading, onLoadMore }: InfiniteScrollSentinelProps) {
  const sentinelRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const node = sentinelRef.current
    if (!node || !hasNextPage) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) onLoadMore()
      },
      { rootMargin: PREFETCH_MARGIN },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [hasNextPage, onLoadMore])

  return (
    <div ref={sentinelRef} aria-hidden={!isLoading}>
      {isLoading ? (
        <div className="grid grid-cols-2 gap-[var(--qm-space-1)]">
          {Array.from({ length: SKELETON_COUNT }).map((_, i) => (
            <Skeleton key={i} className="aspect-square w-full" />
          ))}
        </div>
      ) : null}
    </div>
  )
}
