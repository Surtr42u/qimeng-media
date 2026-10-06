/**
 * 自研图片查看器（E5）：详情页图片/动图的全屏查看覆盖层——纯 UI 组件，手势
 * 自理（Pointer Events + CSS transform，零新依赖）；换件回调由页面注入，
 * 本组件不发任何网络请求、不含业务规则（铁律 7 / ADR-0008 逻辑 UI 分层）。
 *
 * 坐标纪律（W-3 进度条归一同款）：一切坐标换算只用 clientX/Y 与
 * getBoundingClientRect() 的纯视觉坐标，禁止 clientX÷clientWidth 类跨参照系
 * 混算。配套的根节点 zoom 反向补偿见 prototype.css .img-viewer（1/1.1，与
 * radix popper wrapper 同公式）——补偿后本子树 1 CSS px == 1 视觉 px，事件
 * 坐标 / 元素 rect / CSS transform 长度三方同参照系，全局 html{zoom:1.1} 下
 * 指针位移与画面位移 1:1 不偏。
 *
 * 层级位：createPortal 挂 body——留在 .asset-overlay（z-index 10 自成层叠
 * 上下文）内部的话，子元素 z 值再高也压不过顶栏搜索弹层；z-index 35 高于
 * 叠加层(10)/搜索面板(20)/顶栏搜索输入(30)，低于 FAB(40)与弹窗族(50/51)。
 * F7（2026-09-08 用户拍板，推翻 E5「FAB 浮于查看器系有意」约定）：刷新 FAB
 * 在详情叠加期已由 AppShell 条件卸载（查看器只能在详情叠加内打开），故
 * 查看器期 FAB 实际不可达，40>35 仅是剩余的理论层级位，无遮挡事实。
 *
 * 手势（触摸/鼠标统一 Pointer Events，不引 touch 库）：双指捏合缩放（两指
 * 距离比，clamp 0.5~5x）/ 鼠标滚轮缩放（桌面端 2026-10-04 新增，焦点=光标点，
 * 与捏合同公式同边界）/ 双击放大 1.8x·再双击还原 / 放大态单指拖拽平移
 * （平移按图片边缘收敛）/ 未放大态横滑换上一件·下一件（回调存在才启用）/
 * 单击切沉浸 chrome（延迟判定与双击共存）/ Esc 退出。动效只用
 * opacity+transform+--qm-* token，不碰 zoom；prefers-reduced-motion 由
 * 全局归零层自然覆盖。
 *
 * 混合媒体语义（F2·P3c）：查看器开着横滑进视频项时由挂载方按 !isVideo 卸载
 * 本组件（视频不接图片查看器）；viewerOpen 是否随之复位由挂载方决定——
 * 设计语义与后果记录在 AssetDetailPage 挂载处注释，勿当 bug 修。
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { ChevronLeft, ChevronRight, X } from 'lucide-react'
import {
  DOUBLE_TAP_MS,
  DOUBLE_TAP_SCALE,
  DOUBLE_TAP_SLOP_PX,
  DRAG_SLOP_PX,
  IDENTITY,
  MAX_SCALE,
  MIN_SCALE,
  SINGLE_TAP_DELAY_MS,
  SWIPE_SWITCH_PX,
  WHEEL_ZOOM_SENSITIVITY,
  clampOffset,
  focusPreservingTransform,
  type Gesture,
  type Point,
  type Transform,
} from '@/lib/image-viewer-math'

export interface ImageViewerProps {
  /** 原件直链（detail.origUrl——查看永远用原件直链，组件不改发请求） */
  src: string
  alt: string
  onClose: () => void
  /** 换上一件/下一件（上下文存在且未到边界时由页面注入；缺省=无切换 UI 与手势） */
  onPrev?: () => void
  onNext?: () => void
  /** 查看器序号（1 起）与上下文总数；缺省不渲染序号 */
  position?: { index: number; total: number }
  /** 相邻预载直链（页面按 PRELOAD_AROUND 从快照 origUrls 切片；空=不预载） */
  preloadUrls?: string[]
}

/** 平移收敛与保焦点缩放公式在 lib/image-viewer-math.ts（纯函数 + 单测锁定） */

/**
 * 画布（手势与图片渲染层）。以 key=src 由外层重挂实现换件复位：transform/
 * 手势现场随新挂载自然归零，避免 setState-in-effect 式的命令复位。
 */
function ViewerCanvas({
  src,
  alt,
  onPrev,
  onNext,
  onTap,
}: {
  src: string
  alt: string
  onPrev?: () => void
  onNext?: () => void
  /** 单击确认（已排除双击/拖拽）：外层切沉浸 chrome */
  onTap: () => void
}) {
  const canvasRef = useRef<HTMLDivElement>(null)
  const imgRef = useRef<HTMLImageElement>(null)
  const [transform, setTransform] = useState<Transform>(IDENTITY)
  const [animate, setAnimate] = useState(false)
  // 手势现场全放 ref：move 高频更新不触发重渲染，渲染只吃 transform
  const pointers = useRef(new Map<number, Point>())
  const gesture = useRef<Gesture | null>(null)
  const lastTap = useRef<{ time: number; x: number; y: number } | null>(null)
  const singleTapTimer = useRef<number | null>(null)
  // 事件回调要读「当前 transform」：经 effect 同步（渲染期写 ref 属禁用模式；
  // effect 每渲染跑一次，事件里读到的是上一帧已提交值，对本交互足够）
  const transformRef = useRef(transform)
  useEffect(() => {
    transformRef.current = transform
  })

  useEffect(
    () => () => {
      if (singleTapTimer.current !== null) window.clearTimeout(singleTapTimer.current)
    },
    [],
  )

  /** 当前 transform 下图片的 scale=1 基准视觉尺寸（rect ÷ 倍率，同参照系相除）。
   *  useCallback 空依赖稳定化：只读 ref，供下方滚轮监听器以稳定身份引用
   *  （否则每次渲染换函数身份，exhaustive-deps 会迫使监听器重挂）。 */
  const measureBase = useCallback((): { w: number; h: number } => {
    const rect = imgRef.current?.getBoundingClientRect()
    const scale = transformRef.current.scale || 1
    return { w: (rect?.width ?? 0) / scale, h: (rect?.height ?? 0) / scale }
  }, [])

  const measureViewport = useCallback((): { w: number; h: number } => {
    const rect = canvasRef.current?.getBoundingClientRect()
    return { w: rect?.width ?? 0, h: rect?.height ?? 0 }
  }, [])

  /** 画布局部坐标（clientX/Y − 画布 rect 左上，纯视觉坐标相减） */
  const localPoint = useCallback((clientX: number, clientY: number): Point => {
    const rect = canvasRef.current?.getBoundingClientRect()
    return { x: clientX - (rect?.left ?? 0), y: clientY - (rect?.top ?? 0) }
  }, [])

  /** 鼠标滚轮缩放（2026-10-04 用户拍板新增，桌面端交互）：native 非被动监听——
   *  React 合成 wheel 在根容器上是 passive，preventDefault 无效。逐事件按
   *  deltaY 指数因子缩放（滚轮格点与触控板两指平滑同式），焦点=光标点，
   *  公式与捏合/双击共用 focusPreservingTransform，clamp 0.5~5 同边界；
   *  直接写 transform 不设过渡（触控板连续小步跟手，与捏合同口径）。 */
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const onWheel = (e: WheelEvent): void => {
      e.preventDefault()
      const factor = Math.exp(-e.deltaY * WHEEL_ZOOM_SENSITIVITY)
      const start = transformRef.current
      const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, start.scale * factor))
      if (scale === start.scale) return
      setTransform(focusPreservingTransform(
        start, scale, localPoint(e.clientX, e.clientY), { x: 0, y: 0 },
        measureViewport(), measureBase(),
      ))
    }
    canvas.addEventListener('wheel', onWheel, { passive: false })
    return () => canvas.removeEventListener('wheel', onWheel)
  }, [localPoint, measureViewport, measureBase])

  /** 双击缩放切换：>1x 还原，否则放大到 1.8x（绕点击点，保焦点公式=共享
   *  focusPreservingTransform，双击无中点漂移传 0） */
  const toggleZoom = (point: Point) => {
    const t = transformRef.current
    setAnimate(true)
    if (t.scale > 1) {
      setTransform(IDENTITY)
      return
    }
    setTransform(focusPreservingTransform(
      t, DOUBLE_TAP_SCALE, point, { x: 0, y: 0 }, measureViewport(), measureBase(),
    ))
  }

  /** 原地抬手=点击：双击判定优先，否则挂延迟单击（等可能出现的第二击） */
  const handleTap = (point: Point) => {
    const now = performance.now()
    const last = lastTap.current
    if (
      last &&
      now - last.time <= DOUBLE_TAP_MS &&
      Math.hypot(point.x - last.x, point.y - last.y) <= DOUBLE_TAP_SLOP_PX
    ) {
      lastTap.current = null
      if (singleTapTimer.current !== null) {
        window.clearTimeout(singleTapTimer.current)
        singleTapTimer.current = null
      }
      toggleZoom(point)
      return
    }
    lastTap.current = { time: now, x: point.x, y: point.y }
    // 挂新定时器前必清旧句柄（连击时旧单击不生效，chrome 只切一次）
    if (singleTapTimer.current !== null) window.clearTimeout(singleTapTimer.current)
    singleTapTimer.current = window.setTimeout(() => {
      singleTapTimer.current = null
      onTap()
    }, SINGLE_TAP_DELAY_MS)
  }

  /** 横滑收尾：右滑=上一件、左滑=下一件；不足阈值或无对应邻项回弹。
   *  换件路径不复位 transform——外层按 key=src 重挂画布自动归零 */
  const finishSwipe = (dx: number) => {
    if (dx > SWIPE_SWITCH_PX && onPrev) {
      onPrev()
      return
    }
    if (dx < -SWIPE_SWITCH_PX && onNext) {
      onNext()
      return
    }
    setAnimate(true)
    setTransform(IDENTITY)
  }

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return // 非左键不属手势
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // 指针已失效的竞态：捕获失败不阻塞手势（事件仍会派发到画布）
    }
    const point = localPoint(e.clientX, e.clientY)
    pointers.current.set(e.pointerId, point)
    const pts = [...pointers.current.values()]
    if (pointers.current.size === 1) {
      gesture.current = {
        mode: 'pending',
        start: { ...transformRef.current },
        base: measureBase(),
        viewport: measureViewport(),
        startMid: point,
        focal: point,
        startDist: 0,
      }
      setAnimate(false) // 拖拽期间 transform 跟手，不加过渡
      return
    }
    if (pointers.current.size === 2) {
      // 第二指落下即切捏合（无论此前 pending/pan/swipe），基准沿用本手势已测值
      const [a, b] = pts
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
      const prev = gesture.current
      gesture.current = {
        mode: 'pinch',
        start: { ...transformRef.current },
        base: prev?.base ?? measureBase(),
        viewport: prev?.viewport ?? measureViewport(),
        startMid: mid,
        focal: mid,
        startDist: Math.hypot(a.x - b.x, a.y - b.y) || 1,
      }
    }
  }

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const g = gesture.current
    if (!g || !pointers.current.has(e.pointerId)) return
    const point = localPoint(e.clientX, e.clientY)
    pointers.current.set(e.pointerId, point)
    const pts = [...pointers.current.values()]

    if (g.mode === 'pinch') {
      if (pts.length < 2) return
      const [a, b] = pts
      const dist = Math.hypot(a.x - b.x, a.y - b.y) || 1
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
      // 缩放 = 两指距离比 × 起始倍率，clamp 0.5~5（E5 拍板边界）；
      // 保焦点（一般式）=共享 focusPreservingTransform（焦点=起始双指中点，
      // 中点漂移 = mid−startMid），公式行为由 image-viewer-math.test.ts 锁定
      const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, g.start.scale * (dist / g.startDist)))
      setTransform(focusPreservingTransform(
        g.start, scale, g.focal,
        { x: mid.x - g.startMid.x, y: mid.y - g.startMid.y },
        g.viewport, g.base,
      ))
      return
    }

    if (pts.length !== 1) return
    const dx = pts[0].x - g.startMid.x
    const dy = pts[0].y - g.startMid.y
    if (g.mode === 'pending') {
      if (Math.hypot(dx, dy) < DRAG_SLOP_PX) return
      // 过 slop 定模式：放大态=平移；未放大态有邻项=横滑换件，否则也走平移
      // （scale=1 时平移量被收敛归零 = 无邻项时拖拽锁死，语义安全）
      g.mode = g.start.scale > 1 || (!onPrev && !onNext) ? 'pan' : 'swipe'
    }
    if (g.mode === 'pan') {
      setTransform(clampOffset(g.start.scale, g.start.x + dx, g.start.y + dy, g.base, g.viewport))
    } else {
      // swipe 只跟横向（纵向不跟，换件是横滑语义）
      setTransform({ scale: g.start.scale, x: dx, y: 0 })
    }
  }

  const onPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.delete(e.pointerId)
    const g = gesture.current
    gesture.current = null // 捏合抬一指/平移抬手：整段手势结束，保留当前 transform
    if (!g) return
    const point = localPoint(e.clientX, e.clientY)
    if (g.mode === 'pending') handleTap(point)
    else if (g.mode === 'swipe') finishSwipe(point.x - g.startMid.x)
    // 非点击手势（拖拽/捏合/横滑）结束：清跨手势残留的双击首击记录（P3③）
    if (g.mode !== 'pending') lastTap.current = null
  }

  const onPointerCancel = (e: React.PointerEvent<HTMLDivElement>) => {
    pointers.current.delete(e.pointerId)
    const g = gesture.current
    gesture.current = null
    // F2·P3a：系统打断（pointercancel，如来电/手势导航接管）的手势与 pointup
    // 非 pending 路径同口径清 lastTap——残留的首击记录会把下一次抬手误判成
    // 双击缩放
    lastTap.current = null
    if (g?.mode === 'swipe') {
      // 系统打断的横滑不换件，回弹
      setAnimate(true)
      setTransform(IDENTITY)
    }
  }

  return (
    <div
      ref={canvasRef}
      className="img-viewer__canvas"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
    >
      <img
        ref={imgRef}
        className="img-viewer__img"
        src={src}
        alt={alt}
        draggable={false}
        style={{
          transform: `translate3d(${transform.x}px, ${transform.y}px, 0) scale(${transform.scale})`,
          transition: animate ? `transform var(--qm-duration-base) var(--qm-ease-out)` : 'none',
        }}
      />
    </div>
  )
}

/**
 * 桌面壳（titlebar.js）防穿透与防误触通知：
 * 当全屏查看器关闭时，通知桌面壳重置双击最大化状态机并开启短暂冷却，
 * 彻底杜绝组件卸载后物理第二击穿透到底层顶栏造成误最大化。
 */
function blockWindowMaximize(): void {
  if (typeof window !== 'undefined') {
    const win = window as unknown as { __QIMENG_BLOCK_WINDOW_MAXIMIZE__?: (ms?: number) => void }
    win.__QIMENG_BLOCK_WINDOW_MAXIMIZE__?.(400)
  }
}

/**
 * 查看器整体：portal 挂 body（层级位见文件头注释）+ 沉浸 chrome（关闭钮/
 * n÷总数序号/换件箭头）。chrome 显隐状态放外层——换件（画布重挂）不重置
 * 沉浸态；隐藏态由 visibility 延迟过渡兜底（不可点、不进可达性树）。
 */
export default function ImageViewer({
  src,
  alt,
  onClose,
  onPrev,
  onNext,
  position,
  preloadUrls,
}: ImageViewerProps) {
  const [chromeVisible, setChromeVisible] = useState(true)
  // F2·P3b（aria-modal 焦点管理，手写方案）：根容器（Tab 圈定范围）与关闭钮
  // （打开时的初始焦点落点）
  const rootRef = useRef<HTMLDivElement>(null)
  const closeBtnRef = useRef<HTMLButtonElement>(null)

  // F2·P3b：打开时焦点移入（关闭钮），卸载时归还触发元素——role=dialog +
  // aria-modal 的键盘可达性配套。归还对已离场元素是 no-op（浏览器静默忽略），
  // 无需存活检查。preventScroll：查看器是 fixed 全屏层，聚焦不许可滚动跳动。
  // focus-visible 规则保证鼠标用户的点击打开不会闪焦点描边
  useEffect(() => {
    const prev = document.activeElement
    closeBtnRef.current?.focus({ preventScroll: true })
    return () => {
      if (prev instanceof HTMLElement) prev.focus({ preventScroll: true })
    }
  }, [])

  // Esc 退出（window 级监听；chrome 隐藏态也可退出）+ Tab 循环 trap（F2·P3b：
  // aria-modal 语义下焦点不得逃出对话框）+ 方向键换件（E/F卷审查·P3 清偿：
  // 与横滑换件同映射——左=上一件/右=下一件，回调缺失即不启用），卸载时一并清理
  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') {
        blockWindowMaximize()
        onClose()
        return
      }
      if (e.key === 'ArrowLeft' && onPrev) {
        onPrev()
        return
      }
      if (e.key === 'ArrowRight' && onNext) {
        onNext()
        return
      }
      if (e.key !== 'Tab') return
      const root = rootRef.current
      if (!root) return
      // 可焦点集 = 查看器内未禁用且当前可见的按钮（本组件内只有按钮可聚焦）。
      // 沉浸 chrome 隐藏态是 visibility:hidden（不可点、不进可达性树），须剔除
      const focusables = Array.from(
        root.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'),
      ).filter((el) => window.getComputedStyle(el).visibility !== 'hidden')
      if (focusables.length === 0) {
        // P3-1：chrome 全隐藏（可焦点集为空）也吃掉 Tab——aria-modal 层内
        // 无可落点时不许把焦点放逃到查看器外
        e.preventDefault()
        return
      }
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      const active = document.activeElement
      const inCircle = active instanceof HTMLElement && focusables.includes(active as HTMLButtonElement)
      if (!inCircle) {
        // 焦点在圈外（查看器外 / 停在被沉浸隐藏的 chrome 按钮上）：拉回圈内
        e.preventDefault()
        if (e.shiftKey) last.focus({ preventScroll: true })
        else first.focus({ preventScroll: true })
      } else if (e.shiftKey && active === first) {
        e.preventDefault() // 逆向越界：绕到末尾
        last.focus({ preventScroll: true })
      } else if (!e.shiftKey && active === last) {
        e.preventDefault() // 正向越界：绕回头部
        first.focus({ preventScroll: true })
      }
      // 其余情况焦点已在圈内两钮之间，浏览器默认步进即落在圈内，不拦
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, onPrev, onNext])

  // 相邻预载：new Image() 仅暖浏览器缓存；失败静默（不挂 onerror、不重试）
  useEffect(() => {
    for (const url of preloadUrls ?? []) {
      const img = new Image()
      img.src = url
    }
  }, [preloadUrls])

  return createPortal(
    <div ref={rootRef} className="img-viewer" role="dialog" aria-modal="true" aria-label="图片查看器">
      {/* key=src：换件重挂画布，transform/手势现场自然复位（免命令式复位） */}
      <ViewerCanvas
        key={src}
        src={src}
        alt={alt}
        onPrev={onPrev}
        onNext={onNext}
        onTap={() => setChromeVisible((v) => !v)}
      />
      <div className={`img-viewer__chrome${chromeVisible ? '' : ' img-viewer__chrome--hidden'}`}>
        {position ? (
          <span className="img-viewer__counter">
            {position.index} / {position.total}
          </span>
        ) : null}
        <button
          ref={closeBtnRef}
          type="button"
          className="img-viewer__btn img-viewer__close"
          data-no-maximize="true"
          onMouseDown={(e) => {
            e.stopPropagation()
            blockWindowMaximize()
          }}
          onClick={(e) => {
            e.stopPropagation()
            blockWindowMaximize()
            onClose()
          }}
          aria-label="关闭查看器"
        >
          <X size={18} />
        </button>
        {onPrev ? (
          <button
            type="button"
            className="img-viewer__btn img-viewer__nav img-viewer__nav--prev"
            onClick={onPrev}
            aria-label="上一件"
          >
            <ChevronLeft size={22} />
          </button>
        ) : null}
        {onNext ? (
          <button
            type="button"
            className="img-viewer__btn img-viewer__nav img-viewer__nav--next"
            onClick={onNext}
            aria-label="下一件"
          >
            <ChevronRight size={22} />
          </button>
        ) : null}
      </div>
    </div>,
    document.body,
  )
}
