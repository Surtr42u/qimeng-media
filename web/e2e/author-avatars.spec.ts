import { expect, test } from '@playwright/test'

// 合成图片与作者仅在浏览器拦截层使用。
const avatarPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1sAAAAASUVORK5CYII=', 'base64')

test('管理列表只读取视口附近头像，COS标记与名称分开，滚动后可读取后续作者', async ({ page }, testInfo) => {
  const authors = Array.from({ length: 150 }, (_, index) => ({
    id: `cos_synthetic_${index}`, displayName: `合成作者 ${index}`, type: 'cos', fileCount: index + 1, followed: false,
  }))
  const requests: string[] = []
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.addInitScript(() => {
    localStorage.setItem('qimeng_token', 'synthetic-session')
    localStorage.setItem('qimeng_theme', 'light')
  })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/events') return route.abort()
    if (path === '/api/v1/authors') return route.fulfill({ json: authors })
    if (path.endsWith('/profile/avatar')) {
      requests.push(path)
      return route.fulfill({ contentType: 'image/png', body: avatarPng })
    }
    return route.fulfill({ json: [] })
  })
  await page.goto('/app/authors')
  await page.locator('.a-zones').getByRole('button', { name: 'COS', exact: true }).click()
  const rows = page.locator('.a-list li')
  await expect(rows).toHaveCount(150)
  const firstImage = rows.first().locator('.author-avatar img')
  await expect(firstImage).toBeVisible()
  await expect.poll(() => firstImage.evaluate(image => (image as HTMLImageElement).naturalWidth)).toBe(1)
  expect(requests.length).toBeLessThan(25)
  await expect(rows.first().locator('strong')).toHaveText('合成作者 0')
  await expect(rows.first().locator('.author-type')).toHaveText('COS')
  await expect(page.getByRole('button', { name: '新建作者', exact: true })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('authors-desktop.png'), animations: 'disabled' })
  await rows.last().scrollIntoViewIfNeeded()
  await expect(rows.last().locator('.author-avatar img')).toBeVisible()
  await expect.poll(() => requests.some(path => path.includes('cos_synthetic_149/'))).toBe(true)
  await page.setViewportSize({ width: 390, height: 844 })
  await rows.first().scrollIntoViewIfNeeded()
  await expect(rows.first().getByRole('button', { name: '关注', exact: true })).toBeVisible()
  const width = await page.evaluate(() => ({ content: document.documentElement.scrollWidth, viewport: innerWidth }))
  expect(width.content).toBeLessThanOrEqual(width.viewport)
  await page.screenshot({ path: testInfo.outputPath('authors-mobile.png'), animations: 'disabled' })
})
