import { expect, test, type Page } from '@playwright/test'

/** 所有请求都在浏览器拦截；合成路径/日志不含真实媒体或环境。 */
async function openMaintenance(page: Page, options: { unavailable?: boolean; narrow?: boolean; logsUnavailable?: boolean } = {}) {
  let triggers = 0
  let frames = 0
  await page.addInitScript(() => {
    localStorage.setItem('qimeng_token', 'synthetic-browser-session')
    localStorage.setItem('qimeng_theme', 'light')
  })
  if (options.narrow) await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/system/status') {
      if (options.unavailable) return route.fulfill({ status: 503, json: { message: 'synthetic unavailable' } })
      frames++
      return route.fulfill({ json: {
        cpuPercent: 11, memUsedBytes: 14, memTotalBytes: 32, uptimeSeconds: 2517 + frames * 2,
        netRxBytes: 0, netTxBytes: frames * 2048, version: 'synthetic-preview', devMode: true,
        disks: [{ mount: '/synthetic/data', usedBytes: 64, totalBytes: 100 }, { mount: '/synthetic/media', usedBytes: 80, totalBytes: 100 }],
      } })
    }
    if (path === '/api/v1/local-sync/status') return route.fulfill({ json: {
      enabled: true, paused: false, root: '/synthetic/sync', intervalSeconds: 10,
      syncedTotal: 5, counts: { waitingStable: 0, failed: 1, ignored: 0 },
      items: [{ path: '/synthetic/sync/example.png', state: 'failed', error: 'synthetic retryable', attempts: 1 }], recentSynced: [],
    } })
    if (path === '/api/v1/local-sync/trigger') {
      triggers++
      return route.fulfill({ status: 202, json: {} })
    }
    if (path === '/api/v1/client-logs') {
      if (options.logsUnavailable) return route.fulfill({ status: 503, json: { message: 'synthetic unavailable' } })
      return route.fulfill({ json: { items: Array.from({ length: 7 }, (_, i) => ({ ts: 1_700_000_000_000 + i, level: 'error', message: `synthetic log ${i}`, page: '/app/maintenance' })) } })
    }
    await route.fulfill({ json: [] })
  })
  await page.goto('/app/maintenance')
  return { triggers: () => triggers }
}

test('紧凑概览保留零流量、存储明细、日志和导航，删除重复图和同步入口', async ({ page }) => {
  await openMaintenance(page)
  await expect(page.getByRole('heading', { name: '服务与维护', exact: true })).toBeVisible()
  await expect(page.getByText(/开发模式未关/)).toBeVisible()
  await expect(page.getByRole('region', { name: '接收 / 下行', exact: true }).locator('.monitor-value')).toContainText('0 B', { timeout: 10000 })
  await page.getByText('查看存储明细', { exact: false }).click()
  await expect(page.getByText('/synthetic/data', { exact: true })).toBeVisible()
  await expect(page.getByText('/synthetic/media', { exact: true })).toBeVisible()
  await expect(page.locator('.monitor-network-details')).toHaveCount(0)
  await expect(page.getByRole('link', { name: /本机同步/ })).toHaveCount(0)
  await page.getByRole('link', { name: /客户端异常/ }).click()
  await expect(page.locator('.log-table tbody tr')).toHaveCount(5)
  await page.getByRole('button', { name: '查看全部 7 条', exact: true }).click()
  await expect(page.locator('.log-table tbody tr')).toHaveCount(7)
  await page.getByRole('button', { name: '收起（只看最近 5 条）', exact: true }).click()
  await expect(page.locator('.log-table tbody tr')).toHaveCount(5)
  await page.getByRole('link', { name: /文件管理/ }).click()
  await expect(page).toHaveURL(/\/app\/maintenance\/files$/)
  await page.goBack()
  await page.getByRole('link', { name: /回收站/ }).click()
  await expect(page).toHaveURL(/\/app\/maintenance\/trash$/)
})

test('窄屏及监控失败不阻断维护入口，不把缺失数据显示为零', async ({ page }) => {
  await openMaintenance(page, { unavailable: true, narrow: true })
  await expect(page.getByRole('button', { name: '重试监控', exact: true })).toBeVisible({ timeout: 10000 })
  await expect(page.locator('.monitor-gauge strong')).toHaveText('—')
  await expect(page.getByRole('link', { name: /文件管理/ })).toBeVisible()
  const overflow = await page.locator('.monitor-grid').evaluate(el => el.scrollWidth > el.clientWidth)
  expect(overflow).toBe(false)
})

test('服务器目录自动导入迁入上传页高级入口，保留触发及失败记录', async ({ page }) => {
  const fixture = await openMaintenance(page)
  await page.getByRole('link', { name: /上传文件/ }).click()
  await expect(page.getByRole('heading', { name: '上传文件', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '目录浏览', exact: true })).toHaveCount(0)
  await page.locator('.server-import-panel > summary').click()
  await expect(page.getByText(/成功后移走同步源文件/)).toBeVisible()
  await expect(page.getByRole('button', { name: '立即同步', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '立即同步', exact: true }).click()
  await expect.poll(fixture.triggers).toBe(1)
  await expect(page.getByText('synthetic retryable', { exact: true })).toBeVisible()
})

test('刷新日志失败仍可查上次记录，重试恢复原列表', async ({ page }) => {
  const options = { logsUnavailable: false }
  await openMaintenance(page, options)
  await expect(page.locator('.log-table tbody tr')).toHaveCount(5)
  options.logsUnavailable = true
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByText(/以下保留上次成功记录/)).toBeVisible({ timeout: 10000 })
  await expect(page.locator('.log-table tbody tr')).toHaveCount(5)
  options.logsUnavailable = false
  await page.getByRole('button', { name: '重试日志', exact: true }).click()
  await expect(page.getByText(/客户端异常记录读取失败/)).toBeHidden()
  await expect(page.locator('.log-table tbody tr')).toHaveCount(5)
})
