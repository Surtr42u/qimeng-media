// thumb-size.test.ts：缩略图档位偏好的纯函数与 store 行为锁定。
// localStorage 桩先于模块求值生效（sse.test.ts 同款——lib 在模块顶层读
// localStorage，node 测试环境无此全局），桩为可变 map 支撑持久化断言。
import { afterEach, describe, expect, it, vi } from 'vitest'

const store = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
})

const { applyThumbSize, DEFAULT_THUMB_SIZE, getThumbSize, setThumbSize, subscribeThumbSize } =
  await import('./thumb-size')

afterEach(() => {
  store.clear()
  setThumbSize(DEFAULT_THUMB_SIZE)
})

// 服务端下发 thumbUrl 的真实形态（thumbURL 单点拼装：path?exp=&sig=&size=）
const serverUrl = '/media/thumb/0b0e-...?exp=1790000000&sig=ab12cd34&size=md'

describe('applyThumbSize', () => {
  it('auto/空 URL 原样返回（默认行为零变化）', () => {
    expect(applyThumbSize(serverUrl, 'auto')).toBe(serverUrl)
    expect(applyThumbSize('', 'lg')).toBe('')
  })
  it('sm：改写 size 参数，exp/sig 与参数顺序原样保留（签名锚定路径不受影响）', () => {
    expect(applyThumbSize(serverUrl, 'sm')).toBe(
      '/media/thumb/0b0e-...?exp=1790000000&sig=ab12cd34&size=sm',
    )
  })
  it('lg：同理改写为大图档', () => {
    expect(applyThumbSize(serverUrl, 'lg')).toBe(
      '/media/thumb/0b0e-...?exp=1790000000&sig=ab12cd34&size=lg',
    )
  })
  it('URL 无 size 参数时按有无查询串追加（防御形态）', () => {
    expect(applyThumbSize('/media/thumb/x?exp=1&sig=a', 'sm')).toBe('/media/thumb/x?exp=1&sig=a&size=sm')
    expect(applyThumbSize('/media/thumb/x', 'sm')).toBe('/media/thumb/x?size=sm')
  })
})

describe('档位 store', () => {
  it('默认 auto；setThumbSize 持久化并通知订阅者', () => {
    expect(getThumbSize()).toBe('auto')
    const seen: string[] = []
    const unsub = subscribeThumbSize(() => seen.push(getThumbSize()))
    setThumbSize('sm')
    expect(getThumbSize()).toBe('sm')
    expect(store.get('qimeng_thumb_size')).toBe('sm')
    expect(seen).toEqual(['sm'])
    unsub()
  })
  it('setThumbSize 非法值归一默认档（与模块初始读同一 normalize 面）', () => {
    setThumbSize('4k' as unknown as typeof DEFAULT_THUMB_SIZE)
    expect(getThumbSize()).toBe('auto')
    expect(store.get('qimeng_thumb_size')).toBe('auto')
  })
})
