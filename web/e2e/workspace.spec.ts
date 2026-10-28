import { expect, test, type Page } from '@playwright/test'

/** 全部API与封面在浏览器拦截；不访问真实库，也不改变统计/历史领域算法。 */
async function setup(page: Page, options: { tagFails?: boolean; oldOverview?: boolean; authorsFail?: boolean; followFails?: boolean; favoritesFail?: boolean; onlyImageViews?: boolean } = {}) {
  const state = { ...options, ranges: [] as string[], favoriteRequests: 0, historyRequests: 0, followRequests: 0,
    authors: [{ id: 'author-a', displayName: '青禾', fileCount: 18, followed: true }, { id: 'author-b', displayName: '星野', fileCount: 32, followed: false }] }
  const totals = (viewCount = 0, playCount = 0, seconds = 0) => ({ viewCount, playCount, seconds })
  const image = { id: 'favorite-a', fileName: '收藏照片.jpg', mediaType: 'image', thumbUrl: '/fixture-cover.svg', sizeBytes: 1024, viewCount: 4 }
  await page.addInitScript(() => { localStorage.setItem('qimeng_token', 'synthetic-browser-session'); localStorage.setItem('qimeng_theme', 'light') })
  await page.route('**/fixture-cover.svg*', route => route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180"><rect width="320" height="180" fill="#c7cae6"/></svg>' }))
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const range = url.searchParams.get('range') ?? 'all'
    const scale = range === 'day' ? 2 : range === 'all' ? 4 : 1
    if (path === '/api/v1/stats/overview') {
      state.ranges.push(range)
      if (state.oldOverview) return route.fulfill({ json: { totalFiles: 100, totalSizeBytes: 123456 } })
      return route.fulfill({ json: { totalFiles: 100, stillImageCount: 60, imageCount: 70, animatedImageCount: 10, videoCount: 30, totalSizeBytes: 10737418240,
        viewedFilesCount: 7, viewedAuthorsCount: 3, viewedTagsCount: 4, avgViewsPerFile: 6 * scale,
        windowTotals: totals(42 * scale, state.onlyImageViews ? 0 : 8 * scale, 3600 * scale),
        mediaTypeWindowTotals: state.onlyImageViews ? { image: totals(42 * scale, 0, 3600 * scale), video: totals(), animated_image: totals() } : { image: totals(24 * scale, 0, 1200 * scale), video: totals(12 * scale, 8 * scale, 2100 * scale), animated_image: totals(6 * scale, 0, 300 * scale) },
        sourceWindowTotals: { normal: totals(30 * scale), cos: totals(12 * scale) } } })
    }
    if (path === '/api/v1/stats/trends') return route.fulfill({ json: ['2026-10-25', '2026-10-26'].map(date => ({ start: date, end: date, label: date.slice(5), ...totals(21 * scale, 4 * scale, 1800 * scale), mediaTypes: { image: totals(12 * scale), video: totals(6 * scale, 4 * scale), animated_image: totals(3 * scale) }, sources: { normal: totals(15 * scale), cos: totals(6 * scale) } })) })
    if (path === '/api/v1/stats/top-tags') return route.fulfill({ status: state.tagFails ? 503 : 200, json: state.tagFails ? { message: 'synthetic tag unavailable' } : [{ tag: '风景', views: 18 * scale }] })
    if (path === '/api/v1/stats/top-authors') return route.fulfill({ json: [{ authorId: 'author-a', displayName: '青禾', views: 20 * scale }] })
    if (path === '/api/v1/rankings') return route.fulfill({ json: [image] })
    if (path === '/api/v1/discoveries') return route.fulfill({ json: [{ asset: image, reason: 'unseen' }] })
    if (path === '/api/v1/authors') return route.fulfill({ status: state.authorsFail ? 503 : 200, json: state.authorsFail ? { message: 'synthetic authors unavailable' } : state.authors })
    if (path === '/api/v1/authors/author-a/follow') {
      state.followRequests++
      if (state.followFails) return route.fulfill({ status: 503, json: { message: 'synthetic follow unavailable' } })
      state.authors[0].followed = route.request().postDataJSON().follow
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/v1/libraries') return route.fulfill({ json: [{ id: 'library-a', name: '合成图库', enabled: true }] })
    if (path === '/api/v1/assets') {
      state.favoriteRequests++
      return route.fulfill({ status: state.favoritesFail ? 503 : 200, json: state.favoritesFail ? { message: 'synthetic favorites unavailable' } : { items: [image], totalMatched: 1 } })
    }
    if (path === '/api/v1/history') {
      state.historyRequests++
      const next = url.searchParams.has('cursor')
      return route.fulfill({ json: { items: [{ ...image, id: next ? 'history-b' : 'history-a', fileName: next ? 'needle-海边.mp4' : '旧记录.jpg', mediaType: next ? 'video' : 'image', lastViewedAt: Date.now(), ...(next ? { durationMs: 60000, lastPositionSeconds: 60 } : {}) }], ...(next ? {} : { nextCursor: 'synthetic-history-next' }) } })
    }
    if (path === '/api/v1/assets/history-b') return route.fulfill({ json: { ...image, id: 'history-b', fileName: 'needle-海边.mp4', mediaType: 'video', durationMs: 60000, origUrl: '/synthetic-preview.mp4' } })
    if (path === '/api/v1/config') return route.fulfill({ json: { scan: { workers: 2, thumbEdge: 800 }, upload: { maxBytesMb: 2048, autoAccept: true } } })
    return route.fulfill({ json: [] })
  })
  return state
}

test('数据板块独立失败；窗口、库存与完整榜单保持各自口径', async ({ page }) => {
  const state = await setup(page, { tagFails: true })
  await page.goto('/app/data')
  await expect(page.locator('.data-metric--views')).toContainText('42')
  // 图片24/42对应约158px弧长，不能被旧原型CSS固定为79px。
  await expect(page.locator('.donut .arc-2')).toHaveCSS('stroke-dasharray', /^15[78](?:\.\d+)?px/)
  await expect(page.getByRole('region', { name: '媒体库概况', exact: true })).toContainText('100')
  const tags = page.getByRole('region', { name: '标签榜', exact: true })
  await expect(tags.getByRole('alert')).toContainText('标签榜读取失败', { timeout: 10000 })
  await expect(page.getByRole('heading', { name: '浏览与播放趋势', exact: true })).toBeVisible()
  state.tagFails = false
  await tags.getByRole('button', { name: '重新读取', exact: true }).click()
  await expect(tags.getByRole('button', { name: /风景/ })).toBeVisible()
  await page.getByRole('button', { name: '近 30 天', exact: true }).click()
  await expect(page.locator('.data-metric--views')).toContainText('84')
  await expect(page.getByRole('region', { name: '媒体库概况', exact: true })).toContainText('100')
  const discoveries = page.getByRole('region', { name: '此刻发现', exact: true })
  await expect(discoveries).toContainText('收藏照片.jpg')
  await expect(discoveries).not.toContainText(/未看发现|久别重逢|近期兴趣/)
  await expect(page.getByRole('button', { name: '近 30 天', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await tags.getByRole('button', { name: /风景/ }).press('Enter')
  await expect(page).toHaveURL(/\/app\/collection\/tag\//)
  expect(state.ranges).toContain('day')
})

test('旧统计协议不伪装成零数据，其他统计区域保持可访问', async ({ page }) => {
  await setup(page, { oldOverview: true })
  await page.goto('/app/data')
  await expect(page.getByRole('alert')).toContainText('服务端统计协议较旧', { timeout: 10000 })
  await expect(page.locator('.data-metric--views strong')).toHaveText('—')
  await expect(page.getByRole('heading', { name: '此刻发现', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '作者榜', exact: true })).toBeVisible()
})

test('零浏览类型保留数字图例，但不画误导性的彩色圆点', async ({ page }) => {
  await setup(page, { onlyImageViews: true })
  await page.goto('/app/data')
  await expect(page.locator('.donut .arc')).toHaveCount(1)
  const legend = page.locator('.data-secondary-grid .donut-card .legend')
  await expect(legend.locator('li').nth(1)).toContainText('视频')
  await expect(legend.locator('li').nth(1)).toContainText('0.0%')
})

test('我的分区按需加载，收藏失败不是空收藏；键盘切换可用', async ({ page }) => {
  const state = await setup(page, { favoritesFail: true })
  await page.goto('/app/mine')
  await expect(page.getByRole('link', { name: /青禾/ })).toBeVisible()
  expect(state.favoriteRequests).toBe(0)
  expect(state.historyRequests).toBe(0)
  await page.getByRole('tab', { name: '关注作者', exact: true }).press('ArrowRight')
  await expect(page.getByRole('tab', { name: '收藏作品', exact: true })).toHaveAttribute('aria-selected', 'true')
  const pane = page.getByRole('tabpanel', { name: '收藏作品', exact: true })
  await expect(pane.getByRole('alert')).toContainText('收藏读取失败', { timeout: 10000 })
  await expect(pane.getByRole('heading', { name: '暂无收藏内容', exact: true })).toHaveCount(0)
  state.favoritesFail = false
  await pane.getByRole('button', { name: '重新读取', exact: true }).click()
  await expect(pane.getByText('收藏照片.jpg', { exact: true })).toBeVisible()
  expect(state.historyRequests).toBe(0)
  await page.getByRole('tab', { name: '收藏作品', exact: true }).press('End')
  await expect(page.getByRole('tab', { name: '浏览历史', exact: true })).toHaveAttribute('aria-selected', 'true')
  await expect.poll(() => state.historyRequests).toBeGreaterThan(0)
})

test('关注读取和取消失败可重试，不把失败清空为无关注', async ({ page }) => {
  const state = await setup(page, { authorsFail: true, followFails: true })
  await page.goto('/app/mine')
  const pane = page.getByRole('tabpanel', { name: '关注作者', exact: true })
  await expect(pane.getByRole('alert')).toContainText('关注作者读取失败', { timeout: 10000 })
  await expect(pane.getByRole('heading', { name: '暂无关注作者', exact: true })).toHaveCount(0)
  state.authorsFail = false
  await pane.getByRole('button', { name: '重新读取', exact: true }).click()
  const unfollow = pane.getByRole('button', { name: '取消关注 青禾', exact: true })
  await unfollow.click()
  await expect(page.getByText(/取消关注失败/)).toBeVisible()
  await expect(pane.getByRole('link', { name: /青禾/ })).toBeVisible()
  state.followFails = false
  await unfollow.click()
  await expect(pane.getByRole('heading', { name: '暂无关注作者', exact: true })).toBeVisible()
  expect(state.followRequests).toBe(2)
})

test('历史明确筛选已加载记录，手动续页及离开返回不丢分区和筛选', async ({ page }) => {
  const state = await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/app/mine?tab=history&q=needle')
  const pane = page.getByRole('tabpanel', { name: '浏览历史', exact: true })
  await expect(pane.getByRole('heading', { name: '已加载记录中没有匹配项', exact: true })).toBeVisible()
  expect(state.historyRequests).toBe(1)
  await pane.getByRole('button', { name: '加载更多历史', exact: true }).click()
  const card = pane.getByRole('button', { name: 'needle-海边.mp4', exact: true })
  await expect(card).toBeVisible()
  await expect(pane.getByText('已看完', { exact: true })).toHaveCount(0)
  await expect(pane.getByText('筛选匹配 1 项 · 已加载 2 项', { exact: true })).toBeVisible()
  expect(state.historyRequests).toBe(2)
  await page.getByRole('link', { name: '设置', exact: true }).click()
  await expect(page).toHaveURL(/\/app\/settings$/)
  await page.goBack()
  await expect(page.getByRole('tab', { name: '浏览历史', exact: true })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('searchbox', { name: '筛选已加载历史', exact: true })).toHaveValue('needle')
  await card.press('Enter')
  await expect(page).toHaveURL(/\/app\/asset\/history-b$/)
  await expect(page.locator('.asset-stage')).toBeVisible()
  await expect(page.locator('.asset-stage').getByText('已看完', { exact: true })).toHaveCount(0)
})
