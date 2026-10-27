import { expect, test, type Page } from '@playwright/test'
import { createPreviewApi } from '../../tools/ui-preview/api.mjs'
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
  await shuffle.click()
  await expect(list).toHaveAttribute('aria-busy', 'false')
  await expect(list.locator('.upnext-row').first()).toBeVisible()
  await expect.poll(() => list.evaluate(el => el.scrollTop)).toBe(0)
})
