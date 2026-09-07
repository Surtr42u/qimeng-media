import { describe, expect, it } from 'vitest'
import { assetDetail, inHomeDetailGroup, isAssetDetailPath } from './route-keys'

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
