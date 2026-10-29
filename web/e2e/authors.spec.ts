import { expect, test, type Page } from '@playwright/test'

async function setup(page: Page) {
  const state = { failRead: false, failSave: false, writes: [] as string[][], sources: ['site-a'] }
  await page.addInitScript(() => localStorage.setItem('qimeng_token', 'synthetic-session'))
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/authors') return route.fulfill({ json: [
      { id: 'one', displayName: '作者甲 / Alias', type: 'regular', fileCount: 12 },
      { id: 'cos_two', displayName: '目录作者', type: 'cos', fileCount: 3 },
    ] })
    if (path === '/api/v1/authors/one/sources') {
      if (route.request().method() === 'PUT') {
        state.writes.push(route.request().postDataJSON().sources)
        if (state.failSave) return route.fulfill({ status: 503, json: { message: 'synthetic unavailable' } })
        state.sources = route.request().postDataJSON().sources
      } else if (state.failRead) return route.fulfill({ status: 503, json: { message: 'synthetic unavailable' } })
      return route.fulfill({ json: { sources: state.sources } })
    }
    if (path === '/api/v1/authors/source-vocabulary') return route.fulfill({ json: { sources: ['site-a', 'site-b'] } })
    return route.fulfill({ json: {} })
  })
  return state
}

test('作者编辑读取后可清空来源，失败保留草稿再重试；COS 不提供编辑', async ({ page }, testInfo) => {
  const state = await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/app/authors')
  await expect(page.getByRole('button', { name: '编辑资料', exact: true })).toHaveCount(1)
  await page.getByRole('button', { name: '编辑资料', exact: true }).click()
  await page.getByRole('button', { name: '来源 / 出处', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('author-sources-mobile.png'), fullPage: true })
  await page.getByRole('button', { name: 'site-a', exact: true }).click()
  state.failSave = true
  await page.getByRole('button', { name: '保存来源', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(1)
  await expect(page.getByRole('button', { name: '保存来源', exact: true })).toBeEnabled()
  state.failSave = false
  await page.getByRole('button', { name: '保存来源', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(2)
  expect(state.writes).toEqual([[], []])
  await expect(page.getByText('来源已保存', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('authors-mobile.png'), fullPage: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: testInfo.outputPath('authors-desktop.png'), fullPage: true })
})

test('作者来源读取失败不开放保存；来源草稿关闭先确认', async ({ page }) => {
  const state = await setup(page)
  state.failRead = true
  await page.goto('/app/authors')
  await page.getByRole('button', { name: '编辑资料', exact: true }).click()
  await page.getByRole('button', { name: '来源 / 出处', exact: true }).click()
  await expect(page.getByRole('button', { name: '保存来源', exact: true })).toHaveCount(0)
  state.failRead = false
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await page.getByRole('button', { name: 'site-b', exact: true }).click()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toBeVisible()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  expect(state.writes).toEqual([])
  await expect(page.getByRole('button', { name: '保存来源', exact: true })).toBeEnabled()
})
