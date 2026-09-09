import { describe, expect, it } from 'vitest'
import {
  PROGRESS_REPORT_INTERVAL_MS,
  resolveFlushPosition,
  resolveSwitchFlush,
  resolveUnloadFlush,
  shouldSendProgress,
  sortTimelineTagsByTime,
  type LastPositionPair,
} from './progress-report'

const pair = (assetId: string, positionSeconds: number): LastPositionPair => ({
  assetId,
  positionSeconds,
})

describe('shouldSendProgress', () => {
  it('距上次上报恰好满间隔：应发（>= 边界）', () => {
    expect(shouldSendProgress(1000, 1000 + PROGRESS_REPORT_INTERVAL_MS)).toBe(true)
  })

  it('间隔内（差 1ms）：节流不发', () => {
    expect(shouldSendProgress(1000, 1000 + PROGRESS_REPORT_INTERVAL_MS - 1)).toBe(false)
  })

  it('超过间隔：应发', () => {
    expect(shouldSendProgress(1000, 1000 + PROGRESS_REPORT_INTERVAL_MS + 500)).toBe(true)
  })

  it('首轮 lastSentAt=0：立即发（与 hook 原行为一致）', () => {
    // 只要 now ≥ 默认间隔（真实时钟 epoch 毫秒必然满足），首轮 tick 即上报
    expect(shouldSendProgress(0, PROGRESS_REPORT_INTERVAL_MS)).toBe(true)
  })

  it('自定义 intervalMs 可覆盖默认 5s', () => {
    expect(shouldSendProgress(0, 999, 1000)).toBe(false)
    expect(shouldSendProgress(0, 1000, 1000)).toBe(true)
  })

  it('默认间隔常量为 5000ms（协议注释双写锚点）', () => {
    expect(PROGRESS_REPORT_INTERVAL_MS).toBe(5000)
  })
})

describe('resolveSwitchFlush', () => {
  it('配对归属旧资产：补报该配对', () => {
    expect(resolveSwitchFlush(pair('old', 42.5), 'old')).toEqual({
      assetId: 'old',
      positionSeconds: 42.5,
    })
  })

  it('无配对：不补报', () => {
    expect(resolveSwitchFlush(null, 'old')).toBeNull()
  })

  it('配对不归属 prevAssetId：不补报（防御，防跨资产污染）', () => {
    expect(resolveSwitchFlush(pair('other', 10), 'old')).toBeNull()
  })
})

describe('resolveFlushPosition', () => {
  it('显式位置优先：即使配对归属别的资产也用显式值', () => {
    expect(resolveFlushPosition(99, pair('other', 10), 'current')).toBe(99)
  })

  it('无显式且配对归属当前资产：用配对位置', () => {
    expect(resolveFlushPosition(undefined, pair('current', 30), 'current')).toBe(30)
  })

  it('无显式且配对归属旧资产：null 不上报', () => {
    expect(resolveFlushPosition(undefined, pair('old', 30), 'current')).toBeNull()
  })

  it('无显式且无配对：null 不上报', () => {
    expect(resolveFlushPosition(undefined, null, 'current')).toBeNull()
  })

  it('显式 0（片头位置）是合法值，不可与「未传」混淆', () => {
    expect(resolveFlushPosition(0, null, 'current')).toBe(0)
  })
})

describe('resolveUnloadFlush', () => {
  it('有配对：原样报回（谁的位置报给谁）', () => {
    expect(resolveUnloadFlush(pair('a', 12))).toEqual({ assetId: 'a', positionSeconds: 12 })
  })

  it('无配对：null 不上报', () => {
    expect(resolveUnloadFlush(null)).toBeNull()
  })
})

describe('sortTimelineTagsByTime', () => {
  it('乱序 timeMillis 升序重排', () => {
    const tags = [
      { timeMillis: 3000, id: 'c' },
      { timeMillis: 1000, id: 'a' },
      { timeMillis: 2000, id: 'b' },
    ]
    expect(sortTimelineTagsByTime(tags).map((t) => t.id)).toEqual(['a', 'b', 'c'])
  })

  it('缺 timeMillis / null 视为 0 排前', () => {
    const tags = [{ timeMillis: 100, id: 'late' }, { id: 'missing' }, { timeMillis: null, id: 'nullish' }]
    const sorted = sortTimelineTagsByTime(tags)
    // 0 与 0 稳定排序：missing 在 nullish 前（原相对序）
    expect(sorted.map((t) => t.id)).toEqual(['missing', 'nullish', 'late'])
  })

  it('返回新数组，不改入参引用（select 不得 mutate 缓存数据）', () => {
    const tags = [{ timeMillis: 2 }, { timeMillis: 1 }]
    const snapshot = [...tags]
    const out = sortTimelineTagsByTime(tags)
    expect(out).not.toBe(tags)
    expect(tags).toEqual(snapshot)
    expect(out[0].timeMillis).toBe(1)
  })

  it('空数组：原样空数组', () => {
    expect(sortTimelineTagsByTime([])).toEqual([])
  })
})
