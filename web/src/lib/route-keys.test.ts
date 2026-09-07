import { describe, expect, it } from 'vitest'
import {
  assetDetail,
  collectionPath,
  inHomeDetailGroup,
  isAssetDetailPath,
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
