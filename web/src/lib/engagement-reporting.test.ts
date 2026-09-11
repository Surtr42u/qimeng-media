/**
 * 行为打点口径纯函数锁定（DOMAIN_RULES §5）：
 * dwell 段决策（<1s 过滤 / 非有限值防御 / 秒数四舍五入；2026-09-12 W6 #45
 * 回退 V5 口径 B，恢复「<1s 段不上报」）与 play 双来源单发状态机（同段防重 /
 * pause 重置 / 起播来源在接线层归一；#46 维持不变）。node 环境纯函数，
 * 无假件依赖。
 */
import { describe, expect, it } from 'vitest'
import { decideDwellSegment, stepPlayGate } from './engagement-reporting'

describe('decideDwellSegment（dwell 段是否上报 + 秒数取整）', () => {
  it('<1s 过滤：不足 1s 的停留段不上报（W6 #45 回退口径——浏览 open 事件已计入访问，零值段冗余）', () => {
    expect(decideDwellSegment(1)).toEqual({ report: false, seconds: 0 })
    expect(decideDwellSegment(499)).toEqual({ report: false, seconds: 0 })
    expect(decideDwellSegment(500)).toEqual({ report: false, seconds: 0 })
    expect(decideDwellSegment(999)).toEqual({ report: false, seconds: 0 })
  })

  it('恰 1s 起上报：1000ms→1，四舍五入 1.5s→2，长段不受口径回退影响', () => {
    expect(decideDwellSegment(1000)).toEqual({ report: true, seconds: 1 })
    expect(decideDwellSegment(1500)).toEqual({ report: true, seconds: 2 })
    expect(decideDwellSegment(13_000)).toEqual({ report: true, seconds: 13 })
  })

  it('防御：0ms 段不上报（<1s 过滤天然涵盖）', () => {
    expect(decideDwellSegment(0)).toEqual({ report: false, seconds: 0 })
  })

  it('防御：负值（时钟回拨/脏数据）不上报', () => {
    expect(decideDwellSegment(-1)).toEqual({ report: false, seconds: 0 })
    expect(decideDwellSegment(-5000)).toEqual({ report: false, seconds: 0 })
  })

  it('防御：非有限值（NaN/Infinity）不上报', () => {
    expect(decideDwellSegment(Number.NaN)).toEqual({ report: false, seconds: 0 })
    expect(decideDwellSegment(Number.POSITIVE_INFINITY)).toEqual({ report: false, seconds: 0 })
  })
})

describe('stepPlayGate（play 双来源单发状态机）', () => {
  it('首条起播信号派发上报（art 主路径与 video:play 兜底在接线层归一为同一信号，状态机来源无关）', () => {
    expect(stepPlayGate('idle', 'play')).toEqual({ report: true, state: 'reported' })
  })

  it('同段第二条起播信号跳过：art 先 video 后只发一条（防双源双发）', () => {
    const afterFirst = stepPlayGate('idle', 'play')
    expect(stepPlayGate(afterFirst.state, 'play')).toEqual({ report: false, state: 'reported' })
  })

  it('pause 重置：暂停后重新起播属新起播，重新派发（新会话重置）', () => {
    const afterFirst = stepPlayGate('idle', 'play').state
    const afterPause = stepPlayGate(afterFirst, 'pause')
    expect(afterPause).toEqual({ report: false, state: 'idle' })
    expect(stepPlayGate(afterPause.state, 'play')).toEqual({ report: true, state: 'reported' })
  })

  it('idle 收到 pause：状态不变、不上报（暂停信号幂等）', () => {
    expect(stepPlayGate('idle', 'pause')).toEqual({ report: false, state: 'idle' })
  })

  it('重复 pause 幂等，重置不受次数影响', () => {
    let state = stepPlayGate('idle', 'play').state
    state = stepPlayGate(state, 'pause').state
    state = stepPlayGate(state, 'pause').state
    expect(stepPlayGate(state, 'play').report).toBe(true)
  })

  it('完整时序：起播双源（art→video）→暂停双源→重播双源，恰好两条上报', () => {
    // 信号流=接线层真实顺序（video:play 先于 art 'play' 触发亦可，序列等价）
    const signals: Array<'play' | 'pause'> = ['play', 'play', 'pause', 'pause', 'play', 'play']
    const reports = []
    let state = 'idle' as 'idle' | 'reported'
    for (const event of signals) {
      const step = stepPlayGate(state, event)
      state = step.state
      if (step.report) reports.push(event)
    }
    expect(reports).toHaveLength(2)
  })
})
