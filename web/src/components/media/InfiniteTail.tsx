/**
 * 无限加载尾部三件套（E3 无感加载）：加载中占位 / 到底计数 / 触底哨兵。
 * 相册/集合/搜索/我的收藏/我的历史五个列表原各自复制此段 JSX——按
 * 「第 2 次出现即抽共享」收拢于此；文案与哨兵写法（height:1 + aria-hidden）
 * 单一来源，改动全端生效。加载中行复用 LoadingHint（同一文案的共享件）。
 */

import { LoadingHint } from '@/components/ui/loading-hint'

export interface InfiniteTailProps {
  /** 正在拉取下一页（TanStack Query isFetchingNextPage） */
  isFetchingNextPage: boolean
  /** 还有下一页——哨兵只在还有下一页时挂载，到底即卸载、观察器随之断开 */
  hasNextPage: boolean
  /** 当前已加载条数（>0 且到底时显示「共 N 项 · 到底了」） */
  itemCount: number
  /** useAutoMore 返回的哨兵 callback ref */
  sentinelRef: (node: HTMLDivElement | null) => void
  /** 哨兵额外挂载条件（如集合页 found / 搜索页已输入才存在列表），缺省恒挂 */
  sentinelActive?: boolean
}

export function InfiniteTail({
  isFetchingNextPage,
  hasNextPage,
  itemCount,
  sentinelRef,
  sentinelActive = true,
}: InfiniteTailProps) {
  return (
    <>
      {isFetchingNextPage ? <LoadingHint /> : null}
      {!hasNextPage && itemCount > 0 ? (
        <p className="grid-empty">共 {itemCount} 项 · 到底了</p>
      ) : null}
      {sentinelActive && hasNextPage && (
        <div ref={sentinelRef} style={{ height: 1 }} aria-hidden="true" />
      )}
    </>
  )
}
