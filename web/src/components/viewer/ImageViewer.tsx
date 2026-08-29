/**
 * 图片查看器（详情页媒体区）。
 *
 * 交互规格要点：
 * - 原图直显（origUrl 签名直链），**永不降采样**——用户强偏好：查看永远看原件，
 *   服务端也不生成原尺寸副本（协议 AssetDetail.origUrl 注释），此处不做任何图片缩小/压缩。
 * - 滚轮缩放 0.5~5x，以光标为焦点（translate+scale，origin 左上，公式 t' = p - (p-t)*(s1/s0)）；
 *   双指 pinch 支持（touch-action:none + pointer 距离比）；双击 = 点击点为中心放大 1.8x / 已放大则复位。
 * - 左右切换：按钮 + 触摸横滑（阈值 50px）；切换前提 = 批次存在；200ms 滑入（只动媒体容器）。
 * - placeholder 保留：加载新图期间不清空当前画面（displayUrl 直到 onLoad 才切换，防闪白）。
 * - 单击（非拖动/非缩放操作）切换 chrome。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import type { AssetDetail, AssetSummary } from '@/api/generated'
import { cn } from '@/lib/utils'

/* ------------------------------ 交互阈值（交互规格来源） ------------------------------ */

/** 缩放下限（0.5x）与上限（5x）——交互规格定值 */
const ZOOM_MIN = 0.5
const ZOOM_MAX = 5
/** 双击放大倍率（以点击点为中心）——交互规格定值 */
const ZOOM_DOUBLE = 1.8
/** 判定"已放大"的阈值（放大超过此值后双击=复位；考虑浮点误差留余量） */
const ZOOM_IS_ZOOMED = 1.05
/** 触摸横滑切换阈值（px）——交互规格定值 */
const SWIPE_THRESHOLD_PX = 50
/** 小于该位移视为点击（非拖动）——tap 语义 */
const TAP_MOVE_THRESHOLD_PX = 10
/** 小于该时长视为点击——tap 语义 */
const TAP_MAX_DURATION_MS = 300
/** 切换滑入动画时长（ms）：与 --qm-duration-base 对齐 */
const TRANSITION_MS = 200
/** 滑入起始位移比例（相对容器宽度 30%，200ms 归零） */
const SLIDE_INITIAL_RATIO = 0.3
/** 预加载窗口：当前 ±1 张（交互规格：切换前预加载相邻媒体） */
const PRELOAD_RADIUS = 1

interface ImageViewerProps {
  /** 当前媒体详情（origUrl 为签名直链；加载中可为 undefined，旧画面保留） */
  detail: AssetDetail | undefined
  /** 批次（列表页传入 location.state.items）；undefined = 单张，无左右切换 */
  batch: { items: AssetSummary[]; index: number } | undefined
  /** 确认切换目标（父级负责换 assetId + 上报 open） */
  onNavigate: (nextIndex: number) => void
  /** 单击切换 chrome（仅图片生效） */
  onTap: () => void
}

type GestureState =
  | { mode: 'none' }
  | { mode: 'pan'; startX: number; startY: number; startTime: number; moved: boolean }
  | { mode: 'pinch'; startDist: number; startScale: number; startTx: number; startTy: number }

export function ImageViewer({ detail, batch, onNavigate, onTap }: ImageViewerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const gestureRef = useRef<GestureState>({ mode: 'none' })

  // 当前实际展示的图（placeholder 保留：切换后被显示的 URL 变化是"立即跳变"，
  // 这里拆成 displayUrl（旧图保持）与 pending（新图 onLoad 后才激活）
  const [displayUrl, setDisplayUrl] = useState<string | null>(null)
  const [scale, setScale] = useState(1)
  const [tx, setTx] = useState(0)
  const [ty, setTy] = useState(0)
  // 滑入动画（200ms）：active=false 时容器停在 ±30% 偏移，active=true 后过渡回中
  const [slideAnim, setSlideAnim] = useState<{ dir: -1 | 1; key: number; active: boolean } | null>(null)

  // 滑入动画第二步：双 rAF 确保浏览器先渲染"偏移起始帧"，再翻 active 触发过渡
  useEffect(() => {
    if (!slideAnim || slideAnim.active) return
    const id = requestAnimationFrame(() => {
      requestAnimationFrame(() => setSlideAnim((prev) => (prev ? { ...prev, active: true } : prev)))
    })
    return () => cancelAnimationFrame(id)
  }, [slideAnim])

  /* ---- 新图加载：displayUrl 只在 onLoad 后切换（防闪白） ---- */
  useEffect(() => {
    const target = detail?.origUrl ?? null
    if (!target || target === displayUrl) return
    const image = new Image()
    image.onload = () => {
      setDisplayUrl(target)
      // 切图复位缩放/平移（新图从 1x 开始看）
      setScale(1)
      setTx(0)
      setTy(0)
    }
    image.src = target
    return () => {
      image.onload = null
    }
  }, [detail?.origUrl, displayUrl])

  /* ---- 预加载相邻 ±1 张。
     orig 直链需详情接口才能拿到（组件不能调 API），这里用列表 thumbUrl 预热边缘；
     切换时详情请求等待期间旧图保持可见（placeholder 保留）已兜底体验。 ---- */
  useEffect(() => {
    const items = batch?.items ?? []
    const index = batch?.index ?? -1
    if (index < 0) return
    const loaded = new Set<string>()
    const preload = (item: AssetSummary | undefined) => {
      if (!item?.thumbUrl || loaded.has(item.thumbUrl)) return
      loaded.add(item.thumbUrl)
      const image = new Image()
      image.src = item.thumbUrl
    }
    for (let i = index - PRELOAD_RADIUS; i <= index + PRELOAD_RADIUS; i++) {
      preload(items[i])
    }
  }, [batch?.items, batch?.index])

  const resetView = useCallback(() => {
    setScale(1)
    setTx(0)
    setTy(0)
  }, [])

  /* ---- 滚轮缩放（passive:false：必须 preventDefault 阻止页面滚动） ---- */
  useEffect(() => {
    const node = containerRef.current
    if (!node) return
    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const rect = node.getBoundingClientRect()
      const nextScale = clampScale(scale * (event.deltaY < 0 ? ZOOM_STEP_IN : ZOOM_STEP_OUT))
      const next = zoomAt(event.clientX - rect.left, event.clientY - rect.top, scale, tx, ty, nextScale)
      if (next) {
        setScale(next.scale)
        setTx(next.tx)
        setTy(next.ty)
      }
    }
    node.addEventListener('wheel', onWheel, { passive: false })
    return () => node.removeEventListener('wheel', onWheel)
  }, [scale, tx, ty])

  /* ---- 双击：已放大→复位；未放大→以点击点为中心放大 1.8x ---- */
  const handleDoubleClick = (event: React.MouseEvent<HTMLDivElement>) => {
    const rect = containerRef.current?.getBoundingClientRect()
    if (!rect) return
    const px = event.clientX - rect.left
    const py = event.clientY - rect.top
    if (scale > ZOOM_IS_ZOOMED) {
      resetView()
    } else {
      const next = zoomAt(px, py, scale, tx, ty, ZOOM_DOUBLE)
      if (next) {
        setScale(next.scale)
        setTx(next.tx)
        setTy(next.ty)
      }
    }
  }

  /* ---- 触摸手势：单指 tap/横滑切换 + 双指 pinch 缩放 ---- */
  useEffect(() => {
    const node = containerRef.current
    if (!node) return
    const onTouchMove = (event: TouchEvent) => {
      const gesture = gestureRef.current
      // 双指 → pinch 缩放（不触发 swipe）：以双指中点为中心，距离比 = 缩放比
      if (event.touches.length >= 2) {
        event.preventDefault()
        if (gesture.mode === 'pinch') {
          const dist = touchDistance(event.touches)
          const rect = node.getBoundingClientRect()
          const midX = (event.touches[0].clientX + event.touches[1].clientX) / 2 - rect.left
          const midY = (event.touches[0].clientY + event.touches[1].clientY) / 2 - rect.top
          const nextScale = clampScale(gesture.startScale * (dist / gesture.startDist))
          const ratio = nextScale / gesture.startScale
          setScale(nextScale)
          setTx(midX - (midX - gesture.startTx) * ratio)
          setTy(midY - (midY - gesture.startTy) * ratio)
        } else {
          gestureRef.current = {
            mode: 'pinch',
            startDist: touchDistance(event.touches),
            startScale: scale,
            startTx: tx,
            startTy: ty,
          }
        }
        return
      }
      if (gesture.mode === 'pan' && event.touches.length === 1) {
        const g = gesture
        const dx = event.touches[0].clientX - g.startX
        if (!g.moved && Math.abs(dx) > TAP_MOVE_THRESHOLD_PX * 2) {
          gestureRef.current = { ...g, moved: true }
        }
      }
    }
    const onTouchStart = (event: TouchEvent) => {
      // 双指第二指落下：进入 pinch 基准态
      if (event.touches.length === 2) {
        gestureRef.current = {
          mode: 'pinch',
          startDist: touchDistance(event.touches),
          startScale: scale,
          startTx: tx,
          startTy: ty,
        }
        return
      }
      if (event.touches.length === 1) {
        const touch = event.touches[0]
        gestureRef.current = {
          mode: 'pan',
          startX: touch.clientX,
          startY: touch.clientY,
          startTime: Date.now(),
          moved: false,
        }
      }
    }
    const onTouchEnd = (event: TouchEvent) => {
      const gesture = gestureRef.current
      gestureRef.current = { mode: 'none' }
      if (gesture.mode === 'pinch') return
      if (gesture.mode !== 'pan') return
      // 落在自身按钮（左右切换）上的触摸不当作"单击媒体"处理
      if ((event.target as HTMLElement).closest('button')) return
      const changed = event.changedTouches[0]
      const dx = changed.clientX - gesture.startX
      const dy = changed.clientY - gesture.startY
      const duration = Date.now() - gesture.startTime
      if (!gesture.moved && Math.abs(dx) < TAP_MOVE_THRESHOLD_PX && duration < TAP_MAX_DURATION_MS) {
        onTap()
      } else if (Math.abs(dx) >= SWIPE_THRESHOLD_PX && Math.abs(dx) > Math.abs(dy)) {
        const next = dx < 0 ? batchIndex(batch, +1) : batchIndex(batch, -1)
        if (next >= 0) goToInternal(next, dx < 0 ? -1 : 1)
      }
    }
    // touchstart/move 必须 passive:false，preventDefault 阻止浏览器侧滑/捏合手势
    node.addEventListener('touchstart', onTouchStart, { passive: false })
    node.addEventListener('touchmove', onTouchMove, { passive: false })
    node.addEventListener('touchend', onTouchEnd, { passive: false })
    return () => {
      node.removeEventListener('touchstart', onTouchStart)
      node.removeEventListener('touchmove', onTouchMove)
      node.removeEventListener('touchend', onTouchEnd)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scale, tx, ty, batch, onNavigate, onTap])

  /* ---- 左右切换（按钮）--- */
  const goTo = (nextIndex: number) => {
    if (nextIndex < 0 || !batch || nextIndex >= batch.items.length) return
    goToInternal(nextIndex, nextIndex > batch.index ? -1 : 1)
  }

  /** 切换 + 滑入动画（dir：-1 = 从左侧滑入） */
  const goToInternal = (nextIndex: number, dir: -1 | 1) => {
    setSlideAnim({ dir, key: Date.now(), active: false })
    onNavigate(nextIndex)
  }

  const slideFrom = slideAnim ? `${slideAnim.dir * SLIDE_INITIAL_RATIO * 100}%` : null

  return (
    <div
      ref={containerRef}
      className="relative size-full touch-none select-none overflow-hidden"
      style={{ touchAction: 'none' }}
      onDoubleClick={handleDoubleClick}
    >
      {/* 滑入动画容器（只动媒体容器；缩放动作在 img 上，互不干扰）。
          两阶段：active=false 停在偏移位（无过渡）→ 双 rAF 后 active=true 触发过渡回中 */}
      <div
        key={slideAnim?.key ?? 'static'}
        className={cn('flex size-full items-center justify-center', slideAnim?.active && 'transition-[transform,opacity]')}
        style={{
          transform: slideAnim && !slideAnim.active ? `translateX(${slideFrom})` : undefined,
          opacity: slideAnim && !slideAnim.active ? 0 : undefined,
          transitionDuration: slideAnim?.active ? `${TRANSITION_MS}ms` : undefined,
        }}
      >
        {displayUrl ? (
          <img
            src={displayUrl}
            alt=""
            draggable={false}
            className="max-h-full max-w-full object-contain"
            style={{ transform: `translate(${tx}px, ${ty}px) scale(${scale})`, transformOrigin: '0 0' }}
          />
        ) : (
          <div className="grid size-12 place-items-center rounded-full bg-black/50">
            <span className="size-4 animate-spin rounded-full border-2 border-white/80 border-t-transparent" aria-hidden />
          </div>
        )}

        {/* 左右切换按钮（有批次且非边界显示） */}
        {batch && batch.index > 0 ? (
          <button
            type="button"
            aria-label="上一张"
            onClick={() => goTo(batch.index - 1)}
            className="absolute left-[var(--qm-space-2)] top-1/2 -translate-y-1/2 rounded-full bg-black/40 p-2 text-white hover:bg-black/60"
          >
            <ChevronLeft className="size-6" aria-hidden />
          </button>
        ) : null}
        {batch && batch.index < batch.items.length - 1 ? (
          <button
            type="button"
            aria-label="下一张"
            onClick={(e) => {
              e.stopPropagation()
              goTo(batch.index + 1)
            }}
            className="absolute right-[var(--qm-space-2)] top-1/2 -translate-y-1/2 rounded-full bg-black/40 p-2 text-white hover:bg-black/60"
          >
            <ChevronRight className="size-6" aria-hidden />
          </button>
        ) : null}
      </div>
    </div>
  )
}

/* ------------------------------ 纯工具函数（缩放几何） ------------------------------ */

/** 滚轮单步缩放因子（向下滚=缩小；每步 ×0.8 / ×1.25，交互规格区间 0.5~5） */
const ZOOM_STEP_IN = 1.25
const ZOOM_STEP_OUT = 0.8

function clampScale(value: number): number {
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, value))
}

/**
 * 以焦点 (px,py)（相对容器左上）为核心缩放：不变量 = 焦点下的图像点保持不动。
 * 推导：点 p 在变换 translate(t)+scale(s) 下的图像坐标 q=(p-t)/s，缩放前后 q 不变：
 *   (p - t')/s' = (p - t)/s  →  t' = p - (p - t) * (s'/s)
 * 纯函数：输入当前变换输出新变换（返回 null = 无需变化）。
 */
function zoomAt(
  px: number,
  py: number,
  currentScale: number,
  currentTx: number,
  currentTy: number,
  nextScale: number,
): { scale: number; tx: number; ty: number } | null {
  const clamped = clampScale(nextScale)
  if (clamped === currentScale) return null
  const ratio = clamped / currentScale
  return {
    scale: clamped,
    tx: px - (px - currentTx) * ratio,
    ty: py - (py - currentTy) * ratio,
  }
}

/** 双指距离（pinch 缩放基准） */
function touchDistance(touches: TouchList): number {
  const [a, b] = [touches[0], touches[1]]
  return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY)
}

/** 批次内目标序号：+1=下一张、-1=上一张；无批次/越界返回 -1（调用方自行忽略） */
function batchIndex(batch: { items: AssetSummary[]; index: number } | undefined, delta: -1 | 1): number {
  if (!batch) return -1
  const next = batch.index + delta
  return next >= 0 && next < batch.items.length ? next : -1
}
