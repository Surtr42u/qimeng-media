import { authors, createCatalog, libraries, tags } from './fixtures/catalog.mjs'
import { assetPage, facets, offsetPage, trends } from './browse.mjs'

const totals = (viewCount = 0, playCount = 0, seconds = 0) => ({ viewCount, playCount, seconds })
const balanced = { tagRelevance: .22, tagCollection: .15, engagement: .1, recency: .15, likeScore: .05, discovery: .2, freshness: .05, browseDepth: .03, maxRandom: .3 }
const reply = (body, status = 200, headers = {}) => ({ status, body, headers })

/** 有界内存演示API；只读合成夹具，支持的演示写入重启即复原，无后端转发。 */
export function createPreviewApi(now = Date.now()) {
  const catalog = createCatalog(now)
  const people = authors.map(author => ({ ...author, fileCount: catalog.filter(asset => asset.authorId === author.id).length }))
  let statusFrames = 0
  let clientConfig = { scan: { workers: 0, thumbEdge: 800 }, upload: { maxBytesMb: 2147483647, autoAccept: true } }
  let prefs = { ...balanced }
  const overview = range => {
    const points = trends(range, now)
    const sum = items => items.reduce((result, item) => totals(result.viewCount + item.viewCount, result.playCount + item.playCount, result.seconds + item.seconds), totals())
    const window = sum(points)
    const mediaTypeWindowTotals = Object.fromEntries(['image', 'video', 'animated_image'].map(key => [key, sum(points.map(point => point.mediaTypes[key]))]))
    const sourceWindowTotals = Object.fromEntries(['normal', 'cos'].map(key => [key, sum(points.map(point => point.sources[key]))]))
    return { totalFiles: catalog.length, totalSizeBytes: catalog.reduce((sum, asset) => sum + asset.sizeBytes, 0), stillImageCount: catalog.filter(asset => asset.mediaType === 'image').length,
      imageCount: catalog.filter(asset => asset.mediaType !== 'video').length, animatedImageCount: catalog.filter(asset => asset.mediaType === 'animated_image').length, videoCount: catalog.filter(asset => asset.mediaType === 'video').length,
      windowTotals: window, mediaTypeWindowTotals,
      sourceWindowTotals, viewedFilesCount: 61, viewedAuthorsCount: 6, viewedTagsCount: 4, avgViewsPerFile: window.viewCount / 61 }
  }
  return (pathname, method = 'GET', params = new URLSearchParams(), body = {}) => {
    // 展示夹具只轮换合成条目，不复制生产选池算法。
    if (pathname === '/api/v1/discoveries' && method === 'GET') {
      const start = Number(params.get('seed') ?? 0) % catalog.length
      const count = Math.min(12, Math.max(1, Number(params.get('limit') ?? 6)))
      return reply(Array.from({ length: count }, (_, index) => ({ asset: catalog[(start + index) % catalog.length], reason: ['unseen', 'rediscover', 'interest'][index % 3] })))
    }
    if (pathname === '/api/v1/auth/dev-login' && method === 'POST') return reply({ token: 'preview-session' })
    if (pathname === '/api/v1/events/view' && method === 'POST') return reply({ outcome: 'ok' }, 202)
    if (pathname === '/api/v1/recommendations/prefs') {
      if (method === 'PUT') { prefs = { ...prefs, ...body }; return reply(prefs) }
      if (method === 'GET') return reply(prefs)
    }
    if (pathname === '/api/v1/assets' && method === 'GET') return reply(assetPage(catalog, params))
    if (pathname === '/api/v1/assets/facets' && method === 'GET') return reply(facets(catalog, params, people))
    if (['/api/v1/recommendations', '/api/v1/rankings'].includes(pathname) && method === 'GET') {
      const page = offsetPage(catalog, params, pathname.endsWith('/rankings'))
      return reply(page.items, 200, { 'X-Qimeng-Next-Offset': String(page.nextOffset) })
    }
    if (pathname === '/api/v1/libraries' && method === 'GET') return reply(libraries.map(library => ({ ...library, fileCount: catalog.filter(asset => asset.libraryId === library.id).length, scanState: 'idle', capabilities: { authorAttach: library.kind === 'normal' } })))
    if (pathname === '/api/v1/authors' && method === 'GET') return reply(people)
    if (pathname === '/api/v1/tags' && method === 'GET') return reply(tags)
    if (pathname === '/api/v1/history' && method === 'GET') {
      const page = assetPage(catalog, new URLSearchParams([...params, ['includeCos', 'true']]))
      return reply({ ...page, items: page.items.map(asset => ({ ...asset, lastViewedAt: Date.parse(asset.modifiedAt) })) })
    }
    if (pathname === '/api/v1/stats/overview' && method === 'GET') return reply(overview(params.get('range')))
    if (pathname === '/api/v1/stats/trends' && method === 'GET') return reply(trends(params.get('range'), now))
    if (pathname === '/api/v1/stats/top-tags' && method === 'GET') return reply(tags.map((tag, index) => ({ tag: tag.name, views: 90 - index * 13 })))
    if (pathname === '/api/v1/stats/top-authors' && method === 'GET') return reply(people.slice(0, 5).map((author, index) => ({ authorId: author.id, displayName: author.displayName, views: 116 - index * 13 })))
    if (pathname === '/api/v1/config') {
      if (method === 'GET') return reply(clientConfig)
      if (method === 'PUT') { clientConfig = { ...body, upload: { ...body.upload, maxBytesMb: 2147483647 } }; return reply(clientConfig) }
    }
    if (pathname === '/api/v1/search/suggestions' && method === 'GET') return reply(people.filter(author => !params.get('q') || author.displayName.includes(params.get('q'))).map(author => ({ type: author.type === 'cos' ? 'cosAuthor' : 'author', name: author.displayName })))
    if (pathname === '/api/v1/sources' && method === 'GET') return reply([...new Set(catalog.filter(asset => !asset.cos).map(asset => asset.source))].map(name => ({ name, fileCount: catalog.filter(asset => !asset.cos && asset.source === name).length })))
    if (pathname === '/api/v1/system/status' && method === 'GET') {
      statusFrames++
      return reply({ devMode: false, version: '虚拟数据预览', uptimeSeconds: 300 + statusFrames * 2, cpuPercent: 12, memUsedBytes: 2 ** 30, memTotalBytes: 8 * 2 ** 30, appRxBytes: statusFrames * 512, appTxBytes: statusFrames * 131072, netRxBytes: statusFrames * 2 ** 20, netTxBytes: statusFrames * 2 ** 21, disks: [] })
    }
    if (pathname === '/api/v1/trash' && method === 'GET') return reply([])
    if (pathname === '/api/v1/client-logs' && method === 'GET') return reply({ items: [] })
    const authorFollow = pathname.match(/^\/api\/v1\/authors\/([^/]+)\/follow$/)
    if (authorFollow && method === 'PUT') { const author = people.find(item => item.id === authorFollow[1]); if (author) { author.followed = !!body.follow; return reply(undefined, 204) } }
    const match = pathname.match(/^\/api\/v1\/assets\/([^/]+)(?:\/(favorite|like|progress|timeline-tags))?$/)
    if (match) {
      if (!match[2] && method !== 'GET') return reply({ code: 'PREVIEW_UNSUPPORTED', message: '虚拟预览不执行文件变更操作' }, 405)
      const asset = catalog.find(item => item.id === match[1])
      if (!asset) return reply({ code: 'PREVIEW_NOT_FOUND', message: '虚拟条目不存在' }, 404)
      if (!match[2] && method === 'GET') return reply({ ...asset, directory: '', relPath: asset.fileName, width: 640, height: 360, tags: tags.filter(tag => asset.tagIds.includes(tag.id)), authors: people.filter(author => author.id === asset.authorId), videoCodec: null })
      if (match[2] === 'timeline-tags' && method === 'GET') return reply([])
      if (match[2] === 'favorite' && method === 'PUT') { asset.isFavorite = !!body.favorite; return reply({ isFavorite: asset.isFavorite }) }
      if (match[2] === 'like' && method === 'PUT') { asset.likedToday = !asset.likedToday; asset.likeCount += asset.likedToday ? 1 : -1; return reply({ likedToday: asset.likedToday, likeCount: asset.likeCount }) }
      if (match[2] === 'progress' && method === 'PUT') { asset.lastPositionSeconds = body.positionSeconds; return reply(undefined, 204) }
    }
    return reply({ code: 'PREVIEW_UNSUPPORTED', message: '此操作未纳入虚拟数据预览，不会连接真实后端。' }, method === 'GET' ? 404 : 405)
  }
}
