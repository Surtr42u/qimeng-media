import { describe, expect, it } from 'vitest'
import {
  clampOffset,
  focusPreservingTransform,
  IDENTITY,
  type Size,
  type Transform,
} from './image-viewer-math'

/** 视口 400×300、图片基准 400×300（1x 恰好铺满）的常用现场 */
const VP: Size = { w: 400, h: 300 }
const BASE: Size = { w: 400, h: 300 }

/** 焦点不变式：内容点 q0=(f−c−t0)/s0 在新 transform 下应仍出现在焦点 f 处
 *  （c=视口中心；渲染模型 = 居中 + 平移 + 缩放）。浮点容差取 1e-9。 */
function focalStillAtFocal(t0: Transform, t1: Transform, focal: { x: number; y: number }): boolean {
  const qx = (focal.x - VP.w / 2 - t0.x) / t0.scale
  const qy = (focal.y - VP.h / 2 - t0.y) / t0.scale
  const sx = VP.w / 2 + t1.x + t1.scale * qx
  const sy = VP.h / 2 + t1.y + t1.scale * qy
  return Math.abs(sx - focal.x) < 1e-9 && Math.abs(sy - focal.y) < 1e-9
}

describe('clampOffset', () => {
  it('图片小于视口：平移收敛为 0（居中锁定）', () => {
    expect(clampOffset(0.5, 999, -999, BASE, VP)).toEqual({ scale: 0.5, x: 0, y: 0 })
  })

  it('图片大于视口：平移夹在 ±(base·scale−viewport)/2', () => {
    // 2x：maxX=(800−400)/2=200，maxY=(600−300)/2=150
    expect(clampOffset(2, 1000, -1000, BASE, VP)).toEqual({ scale: 2, x: 200, y: -150 })
    expect(clampOffset(2, 100, 100, BASE, VP)).toEqual({ scale: 2, x: 100, y: 100 })
  })

  it('scale 原样透传（clamp 不改倍率）', () => {
    expect(clampOffset(1.8, 0, 0, BASE, VP).scale).toBe(1.8)
  })
})

describe('focusPreservingTransform', () => {
  it('恒等起手 2x 绕非中心焦点：焦点下的内容点不动（数值锁定）', () => {
    const t1 = focusPreservingTransform(IDENTITY, 2, { x: 100, y: 50 }, { x: 0, y: 0 }, VP, BASE)
    expect(t1).toEqual({ scale: 2, x: 100, y: 100 })
    expect(focalStillAtFocal(IDENTITY, t1, { x: 100, y: 50 })).toBe(true)
  })

  it('已缩放/已平移起手再放大（一般式核心用例，E5 P2-1 防回归）：焦点不动', () => {
    // t0={2,100,100} → 4x：k=(2−4)/2=−1，t1={4,300,300}（clamp 边界内）
    const t0: Transform = { scale: 2, x: 100, y: 100 }
    const t1 = focusPreservingTransform(t0, 4, { x: 100, y: 50 }, { x: 0, y: 0 }, VP, BASE)
    expect(t1).toEqual({ scale: 4, x: 300, y: 300 })
    expect(focalStillAtFocal(t0, t1, { x: 100, y: 50 })).toBe(true)
  })

  it('双指中点漂移量叠加进平移（捏合移动场景）', () => {
    // 恒等起手、焦点=视口中心、2x、中点漂移 (30,20)：焦点项为 0，仅剩漂移
    const t1 = focusPreservingTransform(
      IDENTITY, 2, { x: 200, y: 150 }, { x: 30, y: 20 }, VP, BASE,
    )
    expect(t1).toEqual({ scale: 2, x: 30, y: 20 })
  })

  it('缩小到图片小于视口：clamp 兜底居中（焦点语义让位于边缘不出视口）', () => {
    const t1 = focusPreservingTransform(IDENTITY, 0.5, { x: 0, y: 0 }, { x: 0, y: 0 }, VP, BASE)
    expect(t1).toEqual({ scale: 0.5, x: 0, y: 0 })
  })
})
