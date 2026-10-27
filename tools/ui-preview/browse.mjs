import { DAY_MS } from './fixtures/catalog.mjs'

const truthy = value => value === 'true' || value === '1'
const values = (params, key) => params.getAll(key).filter(Boolean)
const dayKey = (time, offset) => new Date(Date.parse(time) - offset * 60_000).toISOString().slice(0, 10)

/** 仅用于虚拟数据演示：参数名与分页结构对应OpenAPI，不能作为生产筛选/排序实现。 */
export function filterAssets(catalog, params, omit = '') {
  const partition = params.get('partition') ?? (truthy(params.get('cosOnly')) ? 'cos' : truthy(params.get('includeCos')) || truthy(params.get('favorite')) && params.get('includeCos') !== 'false' ? 'all' : 'regular')
  const offset = Number(params.get('tzOffsetMinutes') ?? 0)
  return catalog.filter(asset => {
    if (omit !== 'partition' && partition !== 'all' && asset.cos !== (partition === 'cos')) return false
    if (params.get('libraryId') && asset.libraryId !== params.get('libraryId')) return false
    if (truthy(params.get('favorite')) && !asset.isFavorite) return false
    if (truthy(params.get('liked')) && !asset.likedToday) return false
    if (omit !== 'author' && values(params, 'source').length && !values(params, 'source').includes(asset.source)) return false
    if (omit !== 'author' && params.get('authorId') && asset.authorId !== params.get('authorId')) return false
    if (omit !== 'character' && values(params, 'character').length && !values(params, 'character').some(value => value.split('+').every(name => asset.characters.includes(name)))) return false
    if (omit !== 'character' && values(params, 'work').length && !values(params, 'work').includes(asset.cosWork)) return false
    if (omit !== 'type' && params.get('mediaType') && params.get('mediaType') !== 'all' && asset.mediaType !== params.get('mediaType')) return false
    if (params.get('q') && ![asset.fileName, asset.source, ...asset.authorNames, ...asset.characters, asset.cosWork ?? ''].some(value => value.includes(params.get('q')))) return false
    const selectedTags = values(params, 'tagIds')
    if (selectedTags.length && !(params.get('tagMode') === 'exact' ? selectedTags.every(id => asset.tagIds.includes(id)) : selectedTags.some(id => asset.tagIds.includes(id)))) return false
    const plays = asset.playCount
    const playRange = params.get('playRange')
    if (playRange === 'none' && plays !== 0 || playRange === 'low' && !(plays >= 1 && plays <= 5) || playRange === 'mid' && !(plays > 5 && plays <= 20) || playRange === 'high' && plays <= 20) return false
    const sizeMb = asset.sizeBytes / 1024 ** 2
    const sizeRange = params.get('sizeRange')
    if (sizeRange === 'lt1m' && sizeMb >= 1 || sizeRange === 'm1to10' && !(sizeMb >= 1 && sizeMb < 10) || sizeRange === 'm10to50' && !(sizeMb >= 10 && sizeMb <= 50) || sizeRange === 'gt50m' && sizeMb <= 50) return false
    const date = dayKey(asset.modifiedAt, offset)
    return !(params.get('dateFrom') && date < params.get('dateFrom') || params.get('dateTo') && date > params.get('dateTo') || params.get('yearFrom') && Number(date.slice(0, 4)) < Number(params.get('yearFrom')) || params.get('yearTo') && Number(date.slice(0, 4)) > Number(params.get('yearTo')))
  })
}

export function assetPage(catalog, params) {
  const all = filterAssets(catalog, params)
  const sort = params.get('sort')
  const field = sort === 'viewCount' ? 'viewCount' : sort === 'sizeBytes' ? 'sizeBytes' : 'modifiedAt'
  const ordered = [...all].sort((a, b) => (a[field] < b[field] ? -1 : a[field] > b[field] ? 1 : a.id.localeCompare(b.id)) * (params.get('order') === 'asc' ? 1 : -1))
  const rawCursor = params.get('cursor')
  if (rawCursor && !/^preview:\d+$/.test(rawCursor)) throw new Error('Invalid preview cursor')
  const start = rawCursor ? Number(rawCursor.slice('preview:'.length)) : 0
  const limit = Math.min(200, Math.max(1, Number(params.get('limit')) || 60))
  const buckets = new Map()
  for (const item of all) { const key = dayKey(item.modifiedAt, Number(params.get('tzOffsetMinutes') ?? 0)); buckets.set(key, (buckets.get(key) ?? 0) + 1) }
  return { items: ordered.slice(start, start + limit), totalMatched: all.length, nextCursor: start + limit < all.length ? `preview:${start + limit}` : null,
    ...(truthy(params.get('dateCounts')) && !rawCursor ? { dateCounts: [...buckets].sort(([a], [b]) => b.localeCompare(a)).map(([date, fileCount]) => ({ date, fileCount })) } : {}) }
}

export function facets(catalog, params, authors) {
  const count = (items, key, name, kind) => ({ key, name, fileCount: items.length, ...(kind ? { kind } : {}) })
  const partitionItems = filterAssets(catalog, params, 'partition')
  const authorItems = filterAssets(catalog, params, 'author')
  const characterItems = filterAssets(catalog, params, 'character')
  const typeItems = filterAssets(catalog, params, 'type')
  const group = (items, keyOf, kind, nameOf = key => key) => [...new Set(items.map(keyOf).filter(Boolean))].map(key => count(items.filter(item => keyOf(item) === key), key, nameOf(key), kind))
  const descending = items => items.sort((a, b) => b.fileCount - a.fileCount || a.name.localeCompare(b.name))
  return {
    partitions: [count(partitionItems, 'all', '全部'), count(partitionItems.filter(item => !item.cos), 'regular', '常规'), count(partitionItems.filter(item => item.cos), 'cos', 'COS')],
    authors: descending([...group(authorItems.filter(item => !item.cos), item => item.source, 'source'), ...group(authorItems.filter(item => item.cos), item => item.authorId, 'author', key => authors.find(author => author.id === key)?.displayName ?? '示例作者')]),
    characters: descending([...group(characterItems.filter(item => !item.cos), item => item.characters[0], 'character'), ...group(characterItems.filter(item => item.cos), item => item.cosWork, 'work')]),
    types: [['all', '全部'], ['image', '图片'], ['animated_image', '动图'], ['video', '视频']].map(([key, name]) => count(typeItems.filter(item => key === 'all' || item.mediaType === key), key, name)),
  }
}

export function offsetPage(catalog, params, ranking = false) {
  const items = filterAssets(catalog, params)
  // 演示seed换批用稳定旋转，不复制生产推荐算法或宣称具有真实热度语义。
  const rotation = items.length ? (Number(params.get('seed')) || 0) % items.length : 0
  const ordered = ranking ? [...items].sort((a, b) => b.viewCount - a.viewCount) : [...items.slice(rotation), ...items.slice(0, rotation)]
  const offset = Math.max(0, Number(params.get('offset')) || 0), limit = Math.min(200, Math.max(1, Number(params.get('limit')) || 60))
  return { items: ordered.slice(offset, offset + limit), nextOffset: offset + limit < ordered.length ? offset + limit : -1 }
}

export function trends(range, now = Date.now()) {
  const count = range === 'all' ? 12 : range === 'day' ? 30 : range === '90d' ? 90 : 7
  return Array.from({ length: count }, (_, index) => {
    const date = new Date(now - (count - index - 1) * DAY_MS).toISOString().slice(0, 10)
    const age = count - index - 1, active = age < 12
    return { start: date, end: date, label: date.slice(5), viewCount: active ? 80 + age % 7 * 11 : 0, playCount: active ? 8 + age % 5 : 0, seconds: active ? 1200 + age % 7 * 180 : 0 }
  })
}
