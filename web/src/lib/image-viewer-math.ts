/**
 * 图片查看器手势数学（E5 自研查看器的纯函数层）：常量、类型与变换公式。
 * 从 components/media/image-viewer.tsx 抽出（2026-09-12 拆分超线文件）——
 * 本文件零 DOM 依赖，公式行为由 image-viewer-math.test.ts 锁定；组件层只做
 * 事件接线与渲染。
 *
 * 坐标纪律：全部为画布局部视觉 px（clientX/Y − 画布 rect 左上），禁止跨
 * 参照系混算（见 image-viewer.tsx 文件头「坐标纪律」）。
 */

/** 缩放边界（E5 拍板口径）：捏合与双击共用，0.5x 概览 ~ 5x 细节 */
export const MIN_SCALE = 0.5
export const MAX_SCALE = 5

/** 双击放大目标倍率（E5 拍板 1.8x；已放大态再双击还原 1x） */
export const DOUBLE_TAP_SCALE = 1.8

/** 相邻预载条数：打开/换件时对前后各 N 件预载原件（对齐旧版 preloadAround
 *  最小档 1 前 1 后）；URL 由页面从上下文快照 origUrls 切片注入，快照无
 *  origUrl 字段则传空（该邻项不预载，切换走详情接口 origUrl） */
export const PRELOAD_AROUND = 1

/** 双击判定：两次抬手的最大间隔（ms）与最大落点偏移（视觉 px），超出算两次单击 */
export const DOUBLE_TAP_MS = 300
export const DOUBLE_TAP_SLOP_PX = 30

/** 单击（切沉浸 chrome）延迟=双击判定窗口：第二击必然落在单击生效前，
 *  结构性排除「单击 chrome 切换 + 双击缩放」同发 */
export const SINGLE_TAP_DELAY_MS = DOUBLE_TAP_MS

/** 位移超过该值（视觉 px）判为拖拽而非点击（防手抖把点击变拖拽） */
export const DRAG_SLOP_PX = 6

/** 未放大态横滑触发换件的最小位移（视觉 px） */
export const SWIPE_SWITCH_PX = 60

/** 画布 transform（渲染态）：scale 倍率，x/y 平移（画布局部视觉 px） */
export interface Transform {
  scale: number
  x: number
  y: number
}

export const IDENTITY: Transform = { scale: 1, x: 0, y: 0 }

/** 二维点（画布局部视觉 px） */
export interface Point {
  x: number
  y: number
}

/** 尺寸对（图片基准尺寸 / 画布视口尺寸共用形态） */
export interface Size {
  w: number
  h: number
}

/** 手势现场：pointerdown 建档、up/cancel 销毁；坐标全部画布局部视觉 px */
export interface Gesture {
  mode: 'pending' | 'pan' | 'swipe' | 'pinch'
  /** 手势起始 transform（增量计算的基准） */
  start: Transform
  /** 图片 scale=1 基准视觉尺寸（建档时测量；pinch 过程不变） */
  base: Size
  /** 画布（=视口）视觉尺寸 */
  viewport: Size
  /** 首指/双指中点起始位置（位移增量参照） */
  startMid: Point
  /** 捏合焦点（=起始双指中点；缩放时保持其画面位置不动） */
  focal: Point
  /** 双指起始间距（为 0 只出现在未进入 pinch 的单指档） */
  startDist: number
}

/** 平移收敛：放大后图片边缘不出视口（图片小于视口时归零居中，视觉 px）。
 *  边界值为 0 时显式归一 +0——Math.min(0, -0) 会产出 -0，渲染虽等价
 *  （translate -0px），但规范输出便于断言与序列化。 */
export function clampOffset(
  scale: number,
  x: number,
  y: number,
  base: Size,
  viewport: Size,
): Transform {
  const clamp = (v: number, max: number): number => {
    const c = Math.min(max, Math.max(-max, v))
    return c === 0 ? 0 : c
  }
  const maxX = Math.max(0, (base.w * scale - viewport.w) / 2)
  const maxY = Math.max(0, (base.h * scale - viewport.h) / 2)
  return { scale, x: clamp(x, maxX), y: clamp(y, maxY) }
}

/**
 * 保焦点缩放（一般式）：t1 = t0 + Δ + (s0−s1)·(f−c−t0)/s0——焦点 f 下的
 * 内容点 p=(f−c−t0)/s0 在缩放前后都停在焦点下。t0≠恒等（起手已平移/已
 * 缩放）也精确；特设式 (s0−s1)·(f−c) 仅 t0=恒等成立，会漂移数百 px（E5
 * P2-1 教训，公式改动须同步测试）。s0 恒 ≥ MIN_SCALE(0.5)，除法无零除。
 *
 * @param start 起始 transform t0（手势建档值）
 * @param scale 目标倍率 s1（调用方负责 clamp 到 [MIN_SCALE, MAX_SCALE]）
 * @param focal 焦点 f（缩放时保持其画面位置不动；双击=点击点、捏合=起始双指中点）
 * @param midDrift 双指中点位移 Δ（双击无中点漂移传 {0,0}；捏合=mid−startMid）
 * @param viewport 画布视口尺寸（中心 c=viewport/2）
 * @param base 图片 scale=1 基准尺寸（clampOffset 收敛用）
 */
export function focusPreservingTransform(
  start: Transform,
  scale: number,
  focal: Point,
  midDrift: Point,
  viewport: Size,
  base: Size,
): Transform {
  const k = (start.scale - scale) / start.scale
  const nx = start.x + midDrift.x + k * (focal.x - viewport.w / 2 - start.x)
  const ny = start.y + midDrift.y + k * (focal.y - viewport.h / 2 - start.y)
  return clampOffset(scale, nx, ny, base, viewport)
}
