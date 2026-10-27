import test from 'node:test'
import assert from 'node:assert/strict'
import { createPreviewApi } from './api.mjs'
import { authors, createCatalog, FIXTURE_COUNT } from './fixtures/catalog.mjs'
import { assetPage, facets } from './browse.mjs'

const now = Date.UTC(2026, 9, 27, 4)

test('合成目录身份稳定，分页守恒；日期计数使用全筛选结果', () => {
  const catalog = createCatalog(now)
  assert.equal(catalog.length, FIXTURE_COUNT)
  assert.deepEqual(catalog, createCatalog(now))
  const first = assetPage(catalog, new URLSearchParams('includeCos=true&limit=60&dateCounts=true&tzOffsetMinutes=-480'))
  assert.equal(first.totalMatched, FIXTURE_COUNT)
  assert.equal(first.dateCounts.reduce((sum, bucket) => sum + bucket.fileCount, 0), FIXTURE_COUNT)
  const second = assetPage(catalog, new URLSearchParams({ includeCos: 'true', limit: '60', cursor: first.nextCursor }))
  const third = assetPage(catalog, new URLSearchParams({ includeCos: 'true', limit: '60', cursor: second.nextCursor }))
  assert.equal(third.nextCursor, null)
  assert.equal(new Set([...first.items, ...second.items, ...third.items].map(asset => asset.id)).size, FIXTURE_COUNT)
  assert.throws(() => assetPage(catalog, new URLSearchParams('cursor=unknown')))
})

test('分区与类型共同筛选，facets排自身且候选kind可回传', () => {
  const catalog = createCatalog(now)
  const params = new URLSearchParams('partition=cos&mediaType=video')
  const buckets = facets(catalog, params, authors)
  assert.equal(buckets.types.find(bucket => bucket.key === 'all').fileCount, catalog.filter(asset => asset.cos).length)
  assert.ok(buckets.authors.every(bucket => bucket.kind === 'author'))
  const picked = buckets.authors[0]
  const selected = assetPage(catalog, new URLSearchParams({ cosOnly: 'true', authorId: picked.key, mediaType: 'video' }))
  assert.ok(selected.items.length > 0)
  assert.ok(selected.items.every(asset => asset.cos && asset.authorId === picked.key && asset.mediaType === 'video'))
})

test('推荐遵守offset头；换seed保留集合并且普通与COS隔离', () => {
  const api = createPreviewApi(now)
  const first = api('/api/v1/recommendations', 'GET', new URLSearchParams('limit=60'))
  const next = api('/api/v1/recommendations', 'GET', new URLSearchParams({ limit: '60', offset: first.headers['X-Qimeng-Next-Offset'] }))
  assert.equal(new Set([...first.body, ...next.body].map(asset => asset.id)).size, 96)
  assert.equal(next.headers['X-Qimeng-Next-Offset'], '-1')
  assert.ok(first.body.every(asset => !asset.cos))
  const cos = api('/api/v1/recommendations', 'GET', new URLSearchParams('cosOnly=true'))
  assert.ok(cos.body.every(asset => asset.cos))
  const reordered = api('/api/v1/recommendations', 'GET', new URLSearchParams('seed=1'))
  assert.notEqual(reordered.body[0].id, first.body[0].id)
})

test('写入只影响当前演示实例，未知与危险操作明确拒绝', () => {
  const api = createPreviewApi(now), other = createPreviewApi(now)
  const asset = api('/api/v1/assets').body.items[0]
  api(`/api/v1/assets/${asset.id}/favorite`, 'PUT', new URLSearchParams(), { favorite: !asset.isFavorite })
  assert.notEqual(api(`/api/v1/assets/${asset.id}`).body.isFavorite, other(`/api/v1/assets/${asset.id}`).body.isFavorite)
  assert.equal(api(`/api/v1/assets/${asset.id}`, 'DELETE').status, 405)
  assert.equal(api('/api/v1/assets/upload', 'POST').status, 405)
  assert.equal(api('/api/v1/not-implemented').status, 404)
  assert.equal(api('/api/v1/stats/overview').body.totalFiles, FIXTURE_COUNT)
})

test('合成窗口扩大保留已有活动，全量与覆盖全部夹具日期的窗口一致', () => {
  const api = createPreviewApi(now)
  const views = range => api('/api/v1/stats/overview', 'GET', new URLSearchParams({ range })).body.windowTotals.viewCount
  assert.ok(views('7d') < views('day'))
  assert.equal(views('day'), views('90d'))
  assert.equal(views('all'), views('90d'))
})

test('趋势分类字段完整，分类计数与窗口汇总同源守恒', () => {
  const api = createPreviewApi(now)
  for (const range of ['7d', 'day', '90d', 'all']) {
    const params = new URLSearchParams({ range })
    const points = api('/api/v1/stats/trends', 'GET', params).body
    const overview = api('/api/v1/stats/overview', 'GET', params).body
    for (const metric of ['viewCount', 'playCount', 'seconds']) {
      assert.equal(points.reduce((sum, point) => sum + point[metric], 0), overview.windowTotals[metric])
      for (const point of points) {
        assert.deepEqual(Object.keys(point.mediaTypes).sort(), ['animated_image', 'image', 'video'])
        assert.deepEqual(Object.keys(point.sources).sort(), ['cos', 'normal'])
        assert.equal(Object.values(point.mediaTypes).reduce((sum, value) => sum + value[metric], 0), point[metric])
        assert.equal(Object.values(point.sources).reduce((sum, value) => sum + value[metric], 0), point[metric])
      }
      for (const key of ['image', 'video', 'animated_image']) assert.equal(points.reduce((sum, point) => sum + point.mediaTypes[key][metric], 0), overview.mediaTypeWindowTotals[key][metric])
      for (const key of ['normal', 'cos']) assert.equal(points.reduce((sum, point) => sum + point.sources[key][metric], 0), overview.sourceWindowTotals[key][metric])
    }
  }
})
