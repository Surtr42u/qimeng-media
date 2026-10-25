import { expect, test, type Page, type Route } from '@playwright/test'

const initial = { scan: { workers: 2, thumbEdge: 800 }, upload: { maxBytesMb: 2048, autoAccept: true } }

async function openSettings(page: Page, onConfig: (route: Route) => Promise<void>) {
  await page.addInitScript(() => localStorage.setItem('qimeng_token', 'synthetic-browser-session'))
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/config') return onConfig(route)
    // 其余列表/偏好/事件全在隔离浏览器内响应；未放行到任何后端。
    await route.fulfill({ json: [] })
  })
  await page.goto('/settings')
}

test('慢保存 A 完成后保留新草稿 B，连续保存发送 B', async ({ page }) => {
  let pending: Route | undefined
  const submissions: unknown[] = []
  await openSettings(page, async (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: initial })
    submissions.push(route.request().postDataJSON())
    pending = route
  })
  const limit = page.getByLabel('单文件上限', { exact: false })
  const save = page.getByRole('button', { name: '保存设置', exact: true })
  await expect(limit).toHaveValue('2048')
  await limit.fill('512')
  await save.click()
  await expect.poll(() => submissions.length).toBe(1)
  await limit.fill('1024')
  await pending!.fulfill({ json: submissions[0] })
  await expect(save).toBeEnabled()
  await expect(limit).toHaveValue('1024')
  await save.click()
  await expect.poll(() => submissions.length).toBe(2)
  expect(submissions[1]).toMatchObject({ upload: { maxBytesMb: 1024 } })
  await pending!.fulfill({ json: submissions[1] })
  await expect(save).toBeEnabled()
  await expect(limit).toHaveValue('1024')
})

test('失败保留草稿，重试成功回填规范化响应', async ({ page }) => {
  let attempts = 0
  await openSettings(page, async (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: initial })
    attempts++
    if (attempts === 1) return route.fulfill({ status: 500, json: { message: 'synthetic failure' } })
    await route.fulfill({ json: { ...initial, upload: { maxBytesMb: 513, autoAccept: true } } })
  })
  const limit = page.getByLabel('单文件上限', { exact: false })
  const save = page.getByRole('button', { name: '保存设置', exact: true })
  await expect(limit).toHaveValue('2048')
  await limit.fill('512')
  await save.click()
  await expect(page.getByText(/设置保存失败/)).toBeVisible()
  await expect(limit).toHaveValue('512')
  await save.click()
  await expect(limit).toHaveValue('513')
  expect(attempts).toBe(2)
})

test('初次查询迟到和焦点刷新不会覆盖本地输入', async ({ page }) => {
  let get: Route | undefined
  await openSettings(page, async (route) => { get = route })
  const limit = page.getByLabel('单文件上限', { exact: false })
  await limit.fill('512')
  await expect.poll(() => get !== undefined).toBe(true)
  await get!.fulfill({ json: initial })
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(limit).toHaveValue('512')
})
