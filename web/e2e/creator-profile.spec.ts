import { expect, test } from '@playwright/test'

test('同名 COS 主页按身份读取，候选先入草稿；失败保留草稿，保存不改 TXT 来源', async ({ page }, testInfo) => {
  const profile = {
    authorId: 'cos_one', revision: 3, nickname: '', biography: '原人工简介', avatarUrl: '',
    homepageUrl: 'https://example.org/creator', evidenceUrls: ['https://example.org/manual'], status: 'candidate',
    xProfile: { url: 'https://x.com/synthetic_one', biography: 'X上的简介原文 🌸\n第二行' },
    autoRefresh: false, feedUrl: '', trackUpdates: false, lastCheckedAt: '', lastSuccessAt: '', lastError: '',
    candidate: { nickname: '公开昵称', biography: '', avatarUrl: '', homepageUrl: 'https://example.org/creator', evidenceUrls: ['https://example.org/about'] }, updates: [],
  }
  const writes: Record<string, unknown>[] = []
  let fail = true
  await page.addInitScript(() => localStorage.setItem('qimeng_token', 'synthetic-session'))
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/authors') return route.fulfill({ json: [
      { id: 'one', displayName: '同名作者', type: 'regular', fileCount: 1 },
      { id: 'cos_one', displayName: '同名作者', type: 'cos', fileCount: 2 },
    ] })
    if (path.endsWith('/profile')) {
      expect(path).toBe('/api/v1/authors/cos_one/profile')
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON() as Record<string, unknown>
        writes.push(body)
        if (fail) return route.fulfill({ status: 409, json: { message: '修订冲突' } })
        return route.fulfill({ json: { ...profile, ...body, revision: 4 } })
      }
      return route.fulfill({ json: profile })
    }
    expect(route.request().method()).toBe('GET')
    if (path.endsWith('/facets')) return route.fulfill({ json: { characters: [], types: [] } })
    return route.fulfill({ json: [] })
  })
  await page.goto('/app/collection/author/同名作者?authorId=cos_one')
  const card = page.getByRole('region', { name: '作者档案', exact: true })
  await expect(card.getByText('COS 作者', { exact: false })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('creator-profile-card.png'), fullPage: true })
  await expect(card.getByLabel('X 简介原文')).toHaveText('X上的简介原文 🌸\n第二行')
  await expect(card.getByRole('link', { name: '前往 X', exact: false })).toHaveAttribute('href', 'https://x.com/synthetic_one')
  await expect(card.getByText('原人工简介', { exact: true })).toHaveCount(0)
  await page.getByRole('group', { name: '作者内容分区' }).getByText('作者新作', { exact: true }).click()
  await expect(page.getByRole('region', { name: '作者新作', exact: true })).toContainText('尚未接入可用的作品发布订阅')
  await page.getByRole('group', { name: '作者内容分区' }).getByText('本地作品', { exact: true }).click()
  await card.getByRole('link', { name: '详细资料', exact: true }).click()
  await card.getByText('资料与维护', { exact: true }).click()
  await card.getByRole('button', { name: '填入候选草稿，继续核对' }).click()
  expect(writes).toHaveLength(0)
  await expect(card.getByLabel('自动检查公开资料', { exact: true })).toBeDisabled()
  await expect(card.getByLabel('检查公开新动态', { exact: true })).toBeDisabled()
  await expect(card.getByRole('textbox', { name: '简介', exact: true })).toHaveValue('原人工简介')
  await card.getByRole('button', { name: '保存作者资料' }).click()
  await expect(card.getByText(/保存失败，草稿已保留/)).toBeVisible()
  await expect(card.getByRole('textbox', { name: '简介', exact: true })).toHaveValue('原人工简介')
  expect(writes[0]).toMatchObject({ revision: 3, biography: '原人工简介', status: 'candidate', evidenceUrls: ['https://example.org/manual', 'https://example.org/about'] })
  fail = false
  await card.getByRole('button', { name: '保存作者资料' }).click()
  await expect(card.getByRole('button', { name: '放弃修改' })).toHaveCount(0)
  expect(writes).toHaveLength(2)
})
