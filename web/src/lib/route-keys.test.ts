import { describe, expect, it } from 'vitest'
import {
  assetDetail,
  assetDetailWithSearch,
  collectionPath,
  inHomeDetailGroup,
  isAssetDetailPath,
  readAssetNavState,
} from './route-keys'

describe('assetDetail', () => {
  it('由 id 生成 /app/asset/:assetId 路径', () => {
    expect(assetDetail('abc123')).toBe('/app/asset/abc123')
  })
})

describe('isAssetDetailPath', () => {
  it('命中：/app/asset/:assetId', () => {
    expect(isAssetDetailPath('/app/asset/x')).toBe(true)
  })

  it('不命中：首页 / 缺 id 段 / 详情后多段 / 根路径', () => {
    expect(isAssetDetailPath('/app/home')).toBe(false)
    expect(isAssetDetailPath('/app/asset')).toBe(false)
    expect(isAssetDetailPath('/app/asset/x/extra')).toBe(false)
    expect(isAssetDetailPath('/')).toBe(false)
  })
})

describe('inHomeDetailGroup', () => {
  it('首页与资产详情在叠加组内，其余页面不在', () => {
    expect(inHomeDetailGroup('/app/home')).toBe(true)
    expect(inHomeDetailGroup('/app/asset/x')).toBe(true)
    expect(inHomeDetailGroup('/app/albums')).toBe(false)
  })
})

describe('collectionPath', () => {
  it('kind/name 拼段：作者与标签两种 kind', () => {
    expect(collectionPath('author', '测试作者二')).toBe(
      '/app/collection/author/%E6%B5%8B%E8%AF%95%E4%BD%9C%E8%80%85%E4%BA%8C',
    )
    expect(collectionPath('tag', '深链测试')).toBe(
      '/app/collection/tag/%E6%B7%B1%E9%93%BE%E6%B5%8B%E8%AF%95',
    )
  })

  it('name 内 URL 结构字符被编码（generatePath 内部 encode，调用方不得预编码）', () => {
    expect(collectionPath('author', 'a/b?c#d')).toBe('/app/collection/author/a%2Fb%3Fc%23d')
  })
})

describe('readAssetNavState', () => {
  it('合法快照（无 origUrls）通过：返回对象恒携带 origUrls 键（未传为显式 undefined）', () => {
    expect(readAssetNavState({ ids: ['a', 'b'], index: 1 })).toStrictEqual({
      ids: ['a', 'b'],
      index: 1,
      origUrls: undefined,
    })
  })

  it('合法快照（origUrls 全 string）原样保留；元素允许 undefined（与 ids 对齐的可选直链）', () => {
    expect(
      readAssetNavState({ ids: ['a', 'b', 'c'], index: 2, origUrls: ['u1', undefined, 'u3'] }),
    ).toStrictEqual({ ids: ['a', 'b', 'c'], index: 2, origUrls: ['u1', undefined, 'u3'] })
  })

  it('state 非 object（null / 原始值 / undefined）→ null（按无上下文处理）', () => {
    expect(readAssetNavState(null)).toBeNull()
    expect(readAssetNavState('x')).toBeNull()
    expect(readAssetNavState(42)).toBeNull()
    expect(readAssetNavState(undefined)).toBeNull()
  })

  it('ids 非数组或缺失 → null（整包拒绝，不降级部分保留）', () => {
    expect(readAssetNavState({ ids: 'a,b', index: 0 })).toBeNull()
    expect(readAssetNavState({ index: 0 })).toBeNull()
  })

  it('ids 含非 string 元素 → null', () => {
    expect(readAssetNavState({ ids: ['a', 2], index: 0 })).toBeNull()
    expect(readAssetNavState({ ids: ['a', null], index: 0 })).toBeNull()
  })

  it('index 非整数 → null（小数 / 字符串 / NaN）', () => {
    expect(readAssetNavState({ ids: ['a'], index: 1.5 })).toBeNull()
    expect(readAssetNavState({ ids: ['a'], index: '0' })).toBeNull()
    expect(readAssetNavState({ ids: ['a'], index: Number.NaN })).toBeNull()
  })

  it('index 越界（负数 / ≥length）→ null', () => {
    expect(readAssetNavState({ ids: ['a'], index: -1 })).toBeNull()
    expect(readAssetNavState({ ids: ['a', 'b'], index: 2 })).toBeNull()
  })

  it('空 ids 数组 → null（index 0 也满足 ≥length 越界判定，空流无上下文）', () => {
    expect(readAssetNavState({ ids: [], index: 0 })).toBeNull()
  })

  it('origUrls 混型（非 string|undefined）或非数组 → null', () => {
    expect(readAssetNavState({ ids: ['a'], index: 0, origUrls: ['u1', 2] })).toBeNull()
    expect(readAssetNavState({ ids: ['a'], index: 0, origUrls: ['u1', null] })).toBeNull()
    expect(readAssetNavState({ ids: ['a'], index: 0, origUrls: 'u1' })).toBeNull()
  })
})

describe('assetDetailWithSearch', () => {
  it('search 空串不加 ?（无查询时结尾就是纯路径）', () => {
    expect(assetDetailWithSearch('a1', '')).toBe('/app/asset/a1')
  })

  it('带查询串原样拼接不重写（?tab/?period 随行使叠加底衬全程保持同一条流）', () => {
    expect(assetDetailWithSearch('a1', '?tab=cos&period=week')).toBe(
      '/app/asset/a1?tab=cos&period=week',
    )
  })

  it('id 不做合法性校验，generatePath 直拼（空 id 吃掉 :assetId 段；特殊字符走 percent-encode）', () => {
    // 实测 react-router 8 generatePath：空参产出无尾斜杠的 /app/asset（函数层不拦截）
    expect(assetDetailWithSearch('', '?tab=cos')).toBe('/app/asset?tab=cos')
    expect(assetDetailWithSearch('a b', '')).toBe('/app/asset/a%20b')
  })
})
