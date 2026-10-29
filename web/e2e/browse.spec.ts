import { expect, test, type Page } from '@playwright/test'
import { createPreviewApi, reviewFixture } from '../../tools/ui-preview/api.mjs'
import { coverSvg } from '../../tools/ui-preview/fixtures/catalog.mjs'

/** 与人工预览共享同一纯合成API；仅拦截浏览器请求，不启动后端或接触真实库。 */
async function setup(page: Page) {
  const api = createPreviewApi(Date.UTC(2026, 9, 27, 4))
  const state = { failAssets: false, failNext: false, seeds: [] as string[] }
  await page.addInitScript(() => { localStorage.setItem('qimeng_token', 'preview-session'); localStorage.setItem('qimeng_theme', 'light') })
  await page.route('**/preview/cover.svg*', route => route.fulfill({ contentType: 'image/svg+xml', body: coverSvg() }))
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url())
    // 本场景不测SSE。短的200响应会被视为反复开流/断流，周期性失效查询并抢走重试状态；
    // 阻断连接避免制造假重连。人工预览server.mjs提供真正保持打开的事件流。
    if (url.pathname === '/api/v1/events') return route.abort()
    if (url.pathname === '/api/v1/recommendations') state.seeds.push(url.searchParams.get('seed') ?? '0')
    if (url.pathname === '/api/v1/assets' && (state.failAssets || state.failNext && url.searchParams.has('cursor'))) return route.fulfill({ status: 503, json: { code: 'SYNTHETIC_FAILURE', message: '合成读取故障' } })
    const result = api(url.pathname, request.method(), url.searchParams, request.postData() ? request.postDataJSON() : {})
    return route.fulfill({ status: result.status, headers: result.headers, ...(result.body === undefined ? {} : { json: result.body }) })
  })
  return state
}

test('首页就近换批更新seed；媒体卡支持键盘打开并返回原流', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/app/home')
  await expect(page.getByRole('heading', { name: '为你推荐', exact: true })).toBeVisible()
  await expect(page.locator('.browse-toolbar')).toContainText('已加载 60 项')
  await page.getByRole('button', { name: '海边随记_002.jpg', exact: true }).press('Enter')
  await expect(page).toHaveURL(/\/app\/asset\/10000000-0000-4000-8000-000000000002/)
  await page.goBack()
  await page.getByRole('button', { name: '换一批', exact: true }).click()
  await expect.poll(() => state.seeds.some(seed => Number(seed) > 0)).toBe(true)
  await expect(page.getByRole('button', { name: '换一批', exact: true })).toBeEnabled()
})

test('长搜索词避开清空按钮，清空后仍可继续输入', async ({ page }) => {
  await setup(page)
  await page.goto('/app/home')
  const input = page.getByPlaceholder('搜索你感兴趣的视频')
  const word = '合成超长搜索标题 '.repeat(16)
  await input.fill(word)
  const gap = await input.evaluate(el => {
    const style = el.ownerDocument.defaultView!.getComputedStyle(el)
    const clear = el.ownerDocument.querySelector('.search-clear')!.getBoundingClientRect()
    return el.getBoundingClientRect().right - parseFloat(style.paddingRight) - clear.left
  })
  expect(gap).toBeLessThanOrEqual(0)
  await page.getByRole('button', { name: '清空搜索词', exact: true }).click()
  await expect(input).toHaveValue('')
  await expect(input).toBeFocused()
})

test('浏览回顾与榜单分开，进入报告保留窗口并呈现服务端比较值', async ({ page }) => {
  await setup(page)
  await page.goto('/app/data?range=day')
  const panel = page.getByRole('region', { name: '浏览回顾', exact: true })
  await expect(panel).toContainText('首次浏览')
  await expect(panel.locator('.rank-item')).toHaveCount(0)
  await panel.getByRole('link', { name: '查看回顾' }).click()
  await expect(page).toHaveURL(/\/app\/data\/review\?range=day/)
  await expect(page.getByRole('region', { name: '探索与回访', exact: true })).toContainText('2026-09-29')
  await expect(page.getByRole('region', { name: '兴趣变化', exact: true })).toContainText('↑ 25.0')
  await expect(page.getByRole('button', { name: '21时，720秒，20次浏览', exact: true })).toBeVisible()
  const interest = page.getByRole('region', { name: '兴趣变化', exact: true })
  await expect(interest).toContainText('25.0%')
  await expect(interest.locator('.review-share-track')).toHaveCount(0)
  await expect(page.locator('.review-activity-legend')).toContainText('图片')
  await expect(page.locator('.review-activity-total')).toContainText('5')
  const hourPicker = page.getByRole('button', { name: '0时，900秒，0次浏览', exact: true })
  await hourPicker.press('End')
  await page.getByRole('button', { name: '23时，1320秒，0次浏览', exact: true }).press('ArrowLeft')
  await page.getByRole('button', { name: '22时，1080秒，0次浏览', exact: true }).press('ArrowLeft')
  await expect(page.locator('.review-hour-detail')).toContainText('21:00–22:00')
  await expect(page.locator('.review-hour-detail')).toContainText('20 次浏览')
  // 刻度与柱条必须共享同一小时列，避免flex等距标签造成错位。
  const alignment = await page.locator('.review-hour-plot').evaluate(el => {
    const bars = el.querySelectorAll('.review-hours > button'), ticks = el.querySelectorAll('.review-hour-labels > span')
    return [0, 6, 12, 18].every(hour => {
      const bar = bars[hour].getBoundingClientRect(), tick = ticks[hour].getBoundingClientRect()
      return Math.abs((bar.left + bar.right) / 2 - (tick.left + tick.right) / 2) < 1
    })
  })
  expect(alignment).toBe(true)
  await page.getByRole('button', { name: '全部', exact: true }).click()
  await expect(page.getByRole('region', { name: '兴趣变化', exact: true })).toContainText('选择近7天')
  await page.getByRole('link', { name: '返回数据' }).click()
  await expect(page).toHaveURL(/\/app\/data\?range=all/)
})

test('浏览回顾请求失败明确重试，零记录不冒充比较结果', async ({ page }) => {
  await setup(page)
  let broken = true
  await page.route('**/api/v1/stats/review*', route => route.fulfill(broken ? { status: 503, json: { message: 'synthetic review failure' } } : {
    json: { ...reviewFixture(), seconds: 0, imageSeconds: 0, videoSeconds: 0, animatedSeconds: 0, viewCount: 0, uniqueFiles: 0, firstViews: 0, repeatViews: 0, activeDays: 0, previousViewCount: 0, comparisonAvailable: false, interests: [], hours: Array.from({ length: 24 }, (_, hour) => ({ hour, views: 0, seconds: 0, imageSeconds: 0, videoSeconds: 0, animatedSeconds: 0 })) },
  }))
  await page.goto('/app/data/review')
  await expect(page.getByRole('alert')).toContainText('浏览回顾读取失败', { timeout: 15000 })
  await expect(page.getByRole('region', { name: '探索与回访', exact: true })).toHaveCount(0)
  broken = false
  await page.getByRole('button', { name: '重新读取', exact: true }).click()
  await expect(page.getByRole('region', { name: '探索与回访', exact: true })).toContainText('这个时段还没有浏览记录')
  await expect(page.getByRole('region', { name: '兴趣变化', exact: true })).toContainText('前后两个时段都有浏览记录后')
  await expect(page.getByRole('group', { name: '24小时浏览时长', exact: true }).getByRole('button')).toHaveCount(24)
})

test('视频网页全屏保留退出入口，底边鼠标可唤出控制栏，Esc可退出', async ({ page }) => {
  await setup(page)
  await page.goto('/app/asset/10000000-0000-4000-8000-000000000001')
  const player = page.locator('.art-video-player')
  await player.locator('.art-control-fullscreenWeb').click()
  await expect(player).toHaveClass(/art-fullscreen-web/)
  const exit = page.getByRole('button', { name: '退出全屏', exact: true })
  await expect(exit).toBeVisible()
  const bounds = await exit.boundingBox()
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
  // 合成夹具不含视频字节；这里验证控件退场与鼠标唤醒，不宣称解码验收。
  await player.evaluate(el => el.classList.remove('art-control-show', 'art-hover'))
  const rect = await player.boundingBox()
  await page.mouse.move(rect!.x + rect!.width / 2, rect!.y + rect!.height - 20)
  await expect(player).toHaveClass(/qimeng-controls-hover/)
  await expect(player.locator('.art-controls')).toHaveCSS('pointer-events', 'auto')
  await exit.click()
  await expect(player).not.toHaveClass(/art-fullscreen-web/)
  await player.locator('.art-control-fullscreenWeb').click()
  await page.keyboard.press('Escape')
  await expect(player).not.toHaveClass(/art-fullscreen-web/)
})

test('相册类型筛选可收起，清空筛选恢复总数；全部选项有选中语义', async ({ page }) => {
  await setup(page)
  await page.goto('/app/albums')
  await expect(page.locator('.browse-toolbar')).toContainText('匹配 144 项')
  const type = page.getByRole('group', { name: '筛选维度', exact: true }).getByRole('button', { name: /^类型/ })
  await type.click()
  const options = page.getByRole('group', { name: '类型选项', exact: true })
  await expect(options.getByRole('button', { name: /^全部/ })).toHaveAttribute('aria-pressed', 'true')
  await options.getByRole('button', { name: /^视频/ }).click()
  await expect(page.locator('.browse-toolbar')).toContainText('匹配 18 项')
  await type.click()
  await expect(options).toBeHidden()
  await page.getByRole('button', { name: '清空筛选', exact: true }).click()
  await expect(page.locator('.browse-toolbar')).toContainText('匹配 144 项')
  await expect(page.getByRole('button', { name: '清空筛选', exact: true })).toHaveCount(0)
})

test('相册读取故障不伪装为空；续页失败重试保留已有条目', async ({ page }) => {
  const state = await setup(page)
  state.failAssets = true
  await page.goto('/app/albums')
  await expect(page.getByRole('alert')).toContainText('相册读取失败', { timeout: 10000 })
  await expect(page.getByText('相册里还没有内容。', { exact: true })).toHaveCount(0)
  state.failAssets = false
  await page.getByRole('button', { name: '重新读取', exact: true }).click()
  const cards = page.locator('#page-albums .card')
  await expect(cards).toHaveCount(60)
  const first = await cards.first().getAttribute('data-id')
  state.failNext = true
  await cards.last().scrollIntoViewIfNeeded()
  await expect(page.getByRole('alert')).toContainText('更多相册内容读取失败', { timeout: 10000 })
  await expect(cards).toHaveCount(60)
  state.failNext = false
  await page.getByRole('button', { name: '重新读取', exact: true }).click()
  await expect.poll(() => cards.count()).toBeGreaterThanOrEqual(120)
  await expect(cards.first()).toHaveAttribute('data-id', first!)
})

test('窄屏首页与相册不横向溢出，批次和筛选入口保持可见', async ({ page }) => {
  await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  for (const route of ['home', 'albums']) {
    await page.goto(`/app/${route}`)
    await expect(page.locator(`#page-${route} .card`).first()).toBeVisible()
    expect(await page.locator(`#page-${route}`).evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await expect(page.getByRole('heading', { name: route === 'home' ? '为你推荐' : '相册', exact: true })).toBeVisible()
    if (route === 'home') await expect(page.getByRole('button', { name: '换一批', exact: true })).toBeVisible()
    else await expect(page.getByRole('button', { name: '更多筛选', exact: true })).toBeVisible()
  }
})


test('详情推荐在卡片内滚动，换批回到顶部且标题操作保持可见', async ({ page }) => {
  await setup(page)
  await page.goto('/app/asset/10000000-0000-4000-8000-000000000001')
  const list = page.getByRole('region', { name: '接下来播放列表', exact: true })
  await expect(list.locator('.upnext-row').first()).toBeVisible()
  expect(await list.locator('.upnext-row').count()).toBeLessThanOrEqual(12)
  await list.scrollIntoViewIfNeeded()
  expect(await list.evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true)
  const alignment = await page.locator('.detail-layout--video').evaluate(el => {
    const stage = el.querySelector('.asset-stage')!.getBoundingClientRect()
    const side = el.querySelector('.detail-side')!.getBoundingClientRect()
    const card = el.querySelector('.upnext-card')!.getBoundingClientRect()
    return { top: Math.abs(stage.top - side.top), bottom: Math.abs(stage.bottom - card.bottom) }
  })
  expect(alignment.top).toBeLessThanOrEqual(1)
  expect(alignment.bottom).toBeLessThanOrEqual(1)
  await list.press('End')
  await expect.poll(() => list.evaluate(el => el.scrollTop)).toBeGreaterThan(0)
  const shuffle = page.locator('.upnext-head').getByRole('button', { name: '换一批', exact: true })
  await expect(shuffle).toBeVisible()
  // 人为挂起新批次响应，覆盖归位先于异步数据到达的顺序。
  let releaseBatch!: () => void
  const batchReady = new Promise<void>(resolve => { releaseBatch = resolve })
  await page.route('**/api/v1/recommendations?**', async route => {
    await batchReady
    await route.fallback()
  })
  await shuffle.click()
  await expect(list).toHaveAttribute('aria-busy', 'true')
  releaseBatch()
  await expect(list).toHaveAttribute('aria-busy', 'false')
  await expect(list.locator('.upnext-row').first()).toBeVisible()
  await expect.poll(() => list.evaluate(el => el.scrollTop)).toBe(0)
})


test('上传返回实际来源工作区，作者管理有小返回且刷新后保留', async ({ page }) => {
  await setup(page)
  await page.goto('/app/maintenance')
  await page.getByRole('link', { name: /^上传文件/ }).click()
  await expect(page.getByRole('link', { name: '服务与维护', exact: true })).toHaveAttribute('href', '/app/maintenance')
  await page.reload()
  await page.getByRole('link', { name: '服务与维护', exact: true }).click()
  await expect(page).toHaveURL(/\/app\/maintenance$/)
  await page.goto('/app/maintenance/files')
  await page.locator('a[href="/app/maintenance/files/upload"]').click()
  await expect(page.getByRole('link', { name: '数据管理', exact: true })).toHaveAttribute('href', '/app/maintenance/files')
  await page.getByRole('link', { name: '数据管理', exact: true }).click()
  await page.locator('a[href="/app/authors"]').click()
  await expect(page.getByRole('link', { name: '数据管理', exact: true })).toHaveAttribute('href', '/app/maintenance/files')
})
