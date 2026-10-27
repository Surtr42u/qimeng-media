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
    if (url.pathname === '/api/v1/events') return route.fulfill({ contentType: 'text/event-stream', body: ': synthetic preview\n\n' })
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
