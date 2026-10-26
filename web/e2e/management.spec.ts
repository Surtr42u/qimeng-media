import { expect, test, type Page, type Route } from '@playwright/test'

async function setup(page: Page, initial: { scanFails?: boolean; deleteFails?: boolean; sourceUnavailable?: boolean; holdSourceSave?: boolean; holdDirectory?: boolean; trashUnavailable?: boolean } = {}) {
  const state = {
    ...initial,
    libraries: [{ id: 'active', name: '运行图库', rootPath: '/synthetic/media', enabled: true, kind: 'normal', fileCount: 2, scanState: 'idle', capabilities: { authorAttach: true } }, { id: 'disabled', name: '停用图库', rootPath: '/synthetic/disabled', enabled: false, kind: 'normal', fileCount: 0, scanState: 'idle', capabilities: { authorAttach: true } }],
    registrations: 0, scans: 0, deletes: 0, toggles: 0, txtDeletes: 0, backupImports: 0, sourceSaves: 0,
    sources: ['site-a'], pendingSource: undefined as Route | undefined, pendingDirectory: undefined as Route | undefined,
    includeCos: '', cursor: '',
  }
  await page.addInitScript(() => { localStorage.setItem('qimeng_token', 'synthetic-browser-session'); localStorage.setItem('qimeng_theme', 'light') })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    if (url.pathname === '/api/v1/libraries') {
      if (method === 'GET') return route.fulfill({ json: state.libraries })
      state.registrations++
      const body = route.request().postDataJSON()
      const library = { ...state.libraries[0], ...body, id: 'new-library' }
      state.libraries.push(library)
      return route.fulfill({ status: 201, json: library })
    }
    if (/\/libraries\/[^/]+\/scan$/.test(url.pathname)) {
      state.scans++
      if (state.scanFails && state.scans === 1) return route.fulfill({ status: 500, json: { message: 'synthetic scan unavailable' } })
      return route.fulfill({ status: 202, json: {} })
    }
    if (/\/libraries\/[^/]+\/enabled$/.test(url.pathname)) {
      state.toggles++
      const library = state.libraries.find(item => item.id === url.pathname.split('/').at(-2))
      if (library) library.enabled = route.request().postDataJSON().enabled
      return route.fulfill({ status: 204 })
    }
    if (/\/libraries\/[^/]+$/.test(url.pathname) && method === 'DELETE') {
      state.deletes++
      if (state.deleteFails && state.deletes === 1) return route.fulfill({ status: 500, json: { message: 'synthetic removal unavailable' } })
      state.libraries = state.libraries.filter(library => library.id !== url.pathname.split('/').at(-1))
      return route.fulfill({ status: 204 })
    }
    if (url.pathname === '/api/v1/config') return route.fulfill({ json: { scan: { workers: 2, thumbEdge: 800 }, upload: { maxBytesMb: 2048, autoAccept: true } } })
    if (url.pathname === '/api/v1/dirs') return route.fulfill({ json: { path: '', fileCount: 2, dirs: [{ path: 'next', fileCount: 1, dirs: [] }] } })
    if (url.pathname === '/api/v1/assets') {
      state.includeCos = url.searchParams.get('includeCos') ?? ''
      state.cursor = url.searchParams.get('cursor') ?? ''
      if (url.searchParams.get('directory') === 'next') {
        if (state.holdDirectory) { state.pendingDirectory = route; return }
        return route.fulfill({ json: { items: [{ id: 'next-file', fileName: 'next-file.jpg', mediaType: 'image' }] } })
      }
      return route.fulfill({ json: { items: [{ id: state.cursor ? 'second' : 'first', fileName: state.cursor ? 'second.jpg' : 'first.jpg', mediaType: 'image' }], ...(state.cursor ? {} : { nextCursor: 'synthetic-next' }) } })
    }
    if (url.pathname === '/api/v1/authors/source-vocabulary') {
      if (method === 'GET') return route.fulfill({ status: state.sourceUnavailable ? 503 : 200, json: state.sourceUnavailable ? { message: 'synthetic unavailable' } : { sources: state.sources } })
      state.sourceSaves++
      state.sources = route.request().postDataJSON().sources
      if (state.holdSourceSave) { state.pendingSource = route; return }
      return route.fulfill({ json: { sources: state.sources } })
    }
    if (url.pathname === '/api/v1/authors/mirror') return route.fulfill({ json: { path: '', fragmentFilename: '' } })
    if (url.pathname === '/api/v1/authors/suggest') return route.fulfill({ json: ['甲', '乙'].filter(name => `作者${name}`.includes(url.searchParams.get('q') ?? '')).map(name => ({ id: name, displayName: `作者${name}`, fileCount: 0 })) })
    if (url.pathname === '/api/v1/authors/import-txt') {
      if (method === 'DELETE') { state.txtDeletes++; return route.fulfill({ status: 204 }) }
      return route.fulfill({ json: state.txtDeletes ? [] : [{ filename: 'authors.txt' }] })
    }
    if (url.pathname === '/api/v1/import/qimeng-backup') { state.backupImports++; return route.fulfill({ json: {} }) }
    if (url.pathname === '/api/v1/trash' && state.trashUnavailable) return route.fulfill({ status: 503, json: { message: 'synthetic unavailable' } })
    if (url.pathname === '/api/v1/local-sync/status') return route.fulfill({ json: { enabled: false, root: '', counts: {}, items: [], recentSynced: [] } })
    await route.fulfill({ json: [] })
  })
  return state
}

test('管理入口按任务分组；上传与文件整理分别进入独立页面', async ({ page }) => {
  await setup(page)
  await page.goto('/app/maintenance/files')
  for (const name of ['入库与整理', '作者与词表', '迁移与恢复']) await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
  // 每张卡仅保留左侧语义图标；删除箭头后不能留下会缩小该图标的 :last-child 样式。
  for (const card of await page.locator('.manage-hub-entry').all()) {
    await expect(card.locator('.manage-entry-top > svg')).toHaveCount(1)
    // 全局 zoom=1.1 会让浏览器回传亚像素值（约35.994px），验证尺寸而非字符串舍入。
    expect(await card.locator('.manage-entry-top > svg').evaluate('icon => parseFloat(getComputedStyle(icon).width)')).toBeCloseTo(36, 1)
  }
  await page.getByRole('link', { name: /上传文件/ }).click()
  await expect(page.getByRole('heading', { name: '上传文件', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '目录浏览', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: '整理已入库文件 →', exact: true }).click()
  await expect(page).toHaveURL(/\/maintenance\/files\/browse$/)
  await expect(page.getByRole('heading', { name: '目录浏览', exact: true })).toBeVisible()
})

test('侧栏返回沿壳内历史回到首页后停止；直达和刷新不退到站外', async ({ page }) => {
  await setup(page)
  // 模拟从应用外进入，浏览器全局 history.length 不能作为是否可返回的依据。
  await page.goto('/outside-app')
  await page.goto('/app/maintenance/files')
  const back = page.locator('.sidebar--back')
  await back.click()
  await expect(page).toHaveURL(/\/app\/home$/)
  await expect(back).toBeDisabled()
  await back.dispatchEvent('click')
  await expect(page).toHaveURL(/\/app\/home$/)
  await page.locator('.settings-item[title="维护"]').click()
  await page.getByRole('link', { name: /文件管理/ }).click()
  await page.getByRole('link', { name: /上传文件/ }).click()
  await back.click()
  await expect(page).toHaveURL(/\/maintenance\/files$/)
  await back.click()
  await expect(page).toHaveURL(/\/maintenance$/)
  await back.click()
  await expect(page).toHaveURL(/\/app\/home$/)
  await expect(back).toBeDisabled()
  await page.locator('.settings-item[title="设置"]').click()
  await page.reload()
  await back.click()
  await expect(page).toHaveURL(/\/app\/home$/)
  await expect(back).toBeDisabled()
})

test('登记成功扫描失败只重试扫描，不重复注册', async ({ page }) => {
  const state = await setup(page, { scanFails: true })
  await page.goto('/app/maintenance/files/libraries')
  await page.getByLabel('库名称', { exact: true }).fill('新增图库')
  await page.getByLabel('服务器根路径', { exact: true }).fill('/synthetic/new')
  await page.getByRole('button', { name: '注册并扫描', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试扫描', exact: true })).toBeVisible()
  expect(state.registrations).toBe(1)
  await page.getByRole('button', { name: '重试扫描', exact: true }).click()
  await expect.poll(() => state.scans).toBe(2)
  expect(state.registrations).toBe(1)
  await expect(page.getByRole('button', { name: '重试扫描', exact: true })).toBeHidden()
})

test('移除库登记失败保留确认目标，可在同一弹窗重试', async ({ page }) => {
  const state = await setup(page, { deleteFails: true })
  await page.goto('/app/maintenance/files/libraries')
  await page.getByRole('switch', { name: '启用媒体库 运行图库', exact: true }).click()
  expect(state.toggles).toBe(0)
  await page.getByRole('button', { name: '确认停用', exact: true }).click()
  await expect.poll(() => state.toggles).toBe(1)
  await expect(page.getByRole('switch', { name: '启用媒体库 运行图库', exact: true })).not.toBeChecked()
  await page.locator('.manage-library').filter({ hasText: '运行图库' }).getByRole('button', { name: '移除库登记', exact: true }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText('收藏、标签、作者等关联')
  await dialog.getByRole('button', { name: '确认移除登记', exact: true }).click()
  await expect.poll(() => state.deletes).toBe(1)
  await expect(dialog.getByRole('button', { name: '确认移除登记', exact: true })).toBeEnabled()
  expect(state.deletes).toBe(1)
  await dialog.getByRole('button', { name: '确认移除登记', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(state.deletes).toBe(2)
})

test('停用库不能作为上传目标，切换作者清空上一作者的批次来源', async ({ page }) => {
  await setup(page)
  await page.goto('/app/maintenance/files/upload')
  const addFiles = page.getByRole('button', { name: '选择文件并立即上传', exact: true })
  await expect(addFiles).toBeDisabled()
  await expect(page.getByRole('button', { name: '停用图库 · 已停用', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '运行图库', exact: true }).click()
  await expect(addFiles).toBeEnabled()
  const author = page.getByLabel('批次作者（可选，联想选择）', { exact: false })
  await author.fill('作者甲')
  await page.getByRole('button', { name: /作者甲.*个文件/ }).click()
  await page.getByRole('button', { name: 'site-a', exact: true }).click()
  await expect(page.getByRole('button', { name: 'site-a', exact: true })).toHaveClass(/active/)
  await author.fill('作者乙')
  await page.getByRole('button', { name: /作者乙.*个文件/ }).click()
  await expect(page.getByRole('button', { name: 'site-a', exact: true })).not.toHaveClass(/active/)
})

test('目录续页不截断，切目录慢请求不展示可操作的旧目录文件', async ({ page }) => {
  const state = await setup(page, { holdDirectory: true })
  await page.goto('/app/maintenance/files/browse')
  await page.getByRole('combobox', { name: '选择库', exact: true }).click()
  await page.getByRole('option', { name: '运行图库', exact: true }).click()
  await expect(page.getByText('first.jpg', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '加载更多文件', exact: true }).click()
  await expect(page.getByText('second.jpg', { exact: true })).toBeVisible()
  expect(state.cursor).toBe('synthetic-next')
  expect(state.includeCos).toBe('true')
  await page.getByRole('button', { name: /^next/ }).click()
  await expect.poll(() => !!state.pendingDirectory).toBe(true)
  await expect(page.locator('.dir-file-name')).toHaveCount(0)
  await state.pendingDirectory!.fulfill({ json: { items: [{ id: 'next-file', fileName: 'next-file.jpg', mediaType: 'image' }] } })
  await expect(page.getByText('next-file.jpg', { exact: true })).toBeVisible()
})

test('来源查询失败禁止空值覆盖，恢复读取后保存期间不可丢失输入', async ({ page }) => {
  const state = await setup(page, { sourceUnavailable: true, holdSourceSave: true })
  await page.goto('/app/maintenance/files/vocabulary')
  await expect(page.getByText(/来源词表读取失败/)).toBeVisible({ timeout: 10000 })
  await expect(page.getByRole('button', { name: '保存来源词表', exact: true })).toBeDisabled()
  expect(state.sourceSaves).toBe(0)
  state.sourceUnavailable = false
  await page.getByRole('button', { name: '重新读取', exact: true }).click()
  const input = page.getByPlaceholder('新来源词，回车加入词表草稿', { exact: true })
  await expect(input).toBeEnabled()
  await input.fill('site-new')
  await input.press('Enter')
  await page.getByRole('button', { name: '保存来源词表', exact: true }).click()
  await expect.poll(() => !!state.pendingSource).toBe(true)
  await expect(input).toBeDisabled()
  await state.pendingSource!.fulfill({ json: { sources: state.sources } })
  await expect(input).toBeEnabled()
  await expect(page.getByRole('button', { name: 'site-new', exact: true })).toBeVisible()
  await input.fill('unsaved-site')
  await input.press('Enter')
  await page.getByRole('link', { name: '数据管理', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toContainText('来源词表或镜像配置')
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.getByRole('button', { name: 'unsaved-site', exact: true })).toBeVisible()
  await page.locator('.sidebar--back').click()
  await expect(page.getByRole('alertdialog')).toContainText('来源词表或镜像配置')
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.getByRole('button', { name: 'unsaved-site', exact: true })).toBeVisible()
  await page.locator('.sidebar--back').click()
  await page.getByRole('button', { name: '放弃并离开', exact: true }).click()
  await expect(page).toHaveURL(/\/app\/home$/)
})

test('回收站失败不是空站；TXT移除先确认；旧v1备份仍在客户端拒绝', async ({ page }) => {
  const state = await setup(page, { trashUnavailable: true })
  await page.goto('/app/maintenance/trash')
  await expect(page.getByText(/不能据此判断回收站为空/)).toBeVisible({ timeout: 10000 })
  await expect(page.getByRole('button', { name: '清空回收站', exact: true })).toBeDisabled()
  await page.goto('/app/maintenance/files/authors-txt')
  await page.getByRole('button', { name: '移除', exact: true }).click()
  expect(state.txtDeletes).toBe(0)
  await page.getByRole('alertdialog').getByRole('button', { name: '确认移除片段', exact: true }).click()
  await expect.poll(() => state.txtDeletes).toBe(1)
  await page.goto('/app/maintenance/files/backup')
  await page.getByLabel('选择本项目v2备份 JSON 文件', { exact: true }).setInputFiles({ name: 'old.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ format: 'qimeng_backup', schemaVersion: 1, data: {} })) })
  await expect(page.getByText(/仅支持本项目的 v2 备份/)).toBeVisible()
  expect(state.backupImports).toBe(0)
})
