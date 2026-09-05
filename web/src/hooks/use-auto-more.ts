/**
 * 触底自动加载（首页推荐/cos/排行榜三 tab 共用，旧版「距底部 ≤6 项提前
 * 增量加载下一页」语义的 web 实现）。
 *
 * 为什么用 IntersectionObserver 而非 scroll 事件：
 *   1) 滚动容器是 AppShell 的 .content 内滚区（不是 window），scroll 监听
 *      需要知道具体容器并逐帧计算 scrollTop/容器高/内容高再手工节流；
 *      IO 由浏览器合成器侧判定，不占主线程、无需节流；
 *   2) IO 的 root 缺省即视口，且交集计算会穿透所有裁剪祖先——无论内容
 *      滚在哪个容器里，哨兵离开可视区就不相交，调用方无需关心滚动容器；
 *   3) 哨兵元素进出提前区各回调一次，天然防抖，不会像 scroll 那样高频触发。
 */

import { useCallback, useEffect, useRef } from 'react'

/** 提前加载项数（旧版语义：距底部 ≤6 项就开始拉下一页） */
const EARLY_LOAD_ITEMS = 6
/** 首页卡片高度近似值（px，grid 两列布局下卡片 + 间距，自算口径）。
 *  只影响提前量大小，不必精确——略小只是提前得少一点。 */
const CARD_HEIGHT_PX = 220

/**
 * 触底监听：enabled 且哨兵进入「视口底部 + 提前量」区域时回调 onHit。
 * 返回哨兵的 callback ref（挂到哨兵元素上；元素挂载/更换/卸载都会
 * 重新接线，适合条件渲染——hasNextPage=false 时卸载哨兵即自动停拉）。
 * onHit 通过 ref 转发，调用方传内联箭头函数也不会反复重建观察器。
 */
export function useAutoMore(
  enabled: boolean,
  onHit: () => void,
): (node: HTMLDivElement | null) => void {
  const onHitRef = useRef(onHit)
  useEffect(() => {
    onHitRef.current = onHit
  })

  const observerRef = useRef<IntersectionObserver | null>(null)
  const disconnect = useCallback(() => {
    observerRef.current?.disconnect()
    observerRef.current = null
  }, [])
  useEffect(() => disconnect, [disconnect])

  return useCallback(
    (node: HTMLDivElement | null) => {
      disconnect()
      if (!node || !enabled) return
      const io = new IntersectionObserver(
        (entries) => {
          if (entries.some((e) => e.isIntersecting)) onHitRef.current()
        },
        // 提前量 = 6 项 × 卡片高，扩在视口底部（对齐旧版 ≤6 项语义）
        { rootMargin: `0px 0px ${EARLY_LOAD_ITEMS * CARD_HEIGHT_PX}px 0px` },
      )
      observerRef.current = io
      io.observe(node)
    },
    [enabled, disconnect],
  )
}
