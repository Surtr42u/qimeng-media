import { expect, test } from '@playwright/test'

test('同名 COS 主页按身份读取，候选先入草稿；失败保留草稿，保存不改 TXT 来源', async ({ page }, testInfo) => {
  const profile = {
    authorId: 'cos_one', revision: 3, nickname: '', biography: '原人工简介', avatarUrl: '',
    homepageUrl: 'https://example.org/creator', evidenceUrls: ['https://example.org/manual'], status: 'candidate',
    xProfile: { url: 'https://x.com/synthetic_one', biography: 'X上的简介原文 🌸\n第二行', biographyZh: '中文简介 🌸\n第二行' },
    autoRefresh: false, feedUrl: '', trackUpdates: false, lastCheckedAt: '', lastSuccessAt: '', lastError: '',
    candidate: { nickname: '公开昵称', biography: '', avatarUrl: '', homepageUrl: 'https://example.org/creator', evidenceUrls: ['https://example.org/about'] }, updates: [],
  }
  const writes: Record<string, unknown>[] = []
  let savedProfile: Record<string, unknown> = profile
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
        savedProfile = { ...savedProfile, ...body, revision: Number(savedProfile.revision) + 1 }
        return route.fulfill({ json: savedProfile })
      }
      return route.fulfill({ json: savedProfile })
    }
    expect(route.request().method()).toBe('GET')
    if (path.endsWith('/facets')) return route.fulfill({ json: { characters: [], types: [] } })
    return route.fulfill({ json: [] })
  })
  await page.goto('/app/collection/author/同名作者?authorId=cos_one')
  const card = page.getByRole('region', { name: '作者档案', exact: true })
  await expect(card.getByText('COS 作者', { exact: false })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('creator-profile-card.png'), fullPage: true })
  await expect(card.getByLabel('X 简介中文')).toHaveText(profile.xProfile.biographyZh)
  await card.getByRole('group', { name: '简介显示语言' }).getByRole('button', { name: '原文', exact: true }).click()
  await expect(card.getByLabel('X 简介原文')).toHaveText('X上的简介原文 🌸\n第二行')
  await card.getByRole('group', { name: '简介显示语言' }).getByRole('button', { name: '中文', exact: true }).click()
  await expect(card.getByLabel('X 简介中文')).toHaveText(profile.xProfile.biographyZh)
  await expect(card.getByRole('link', { name: '前往 X', exact: false })).toHaveAttribute('href', 'https://x.com/synthetic_one')
  await expect(card.getByText('原人工简介', { exact: true })).toHaveCount(0)
  await page.getByRole('group', { name: '作者内容分区' }).getByText('作者新作', { exact: true }).click()
  await expect(page.getByRole('region', { name: '作者新作', exact: true })).toContainText('尚未接入可用的作品发布订阅')
  await page.getByRole('group', { name: '作者内容分区' }).getByText('本地作品', { exact: true }).click()
  await card.getByRole('link', { name: '详细资料', exact: true }).click()
  await expect(card.getByRole('heading', { name: '资料与维护', exact: true })).toBeVisible()
  await expect(card.getByRole('textbox')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('creator-readonly-overview.png'), fullPage: true })
  await card.getByRole('button', { name: '编辑X资料', exact: true }).click()
  await expect(card.getByRole('textbox')).toHaveCount(2)
  await page.setViewportSize({ width: 390, height: 844 })
  const saveBounds = await card.getByRole('button', { name: '保存资料', exact: true }).boundingBox()
  const refreshBounds = await page.getByRole('button', { name: '刷新', exact: true }).boundingBox()
  expect(saveBounds).not.toBeNull()
  expect(refreshBounds).not.toBeNull()
  const save = saveBounds!
  const refresh = refreshBounds!
  expect(save.x + save.width <= refresh.x || save.x >= refresh.x + refresh.width || save.y + save.height <= refresh.y || save.y >= refresh.y + refresh.height).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('creator-x-editor-mobile.png'), fullPage: true })
  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(card.getByRole('textbox', { name: 'X 简介原文', exact: true })).toHaveValue(profile.xProfile.biography)
  await expect(card.getByRole('button', { name: '保存资料', exact: true })).toBeDisabled()
  const languages = card.getByRole('group', { name: '简介编辑语言' })
  await languages.getByRole('button', { name: '中文', exact: true }).click()
  await expect(card.getByRole('textbox', { name: '中文译文', exact: true })).toHaveValue(profile.xProfile.biographyZh)
  await card.getByRole('textbox', { name: '中文译文', exact: true }).fill('草稿中的中文')
  await languages.getByRole('button', { name: '原文', exact: true }).click()
  await expect(card.getByRole('textbox', { name: 'X 简介原文', exact: true })).toHaveValue(profile.xProfile.biography)
  await languages.getByRole('button', { name: '中文', exact: true }).click()
  await expect(card.getByRole('textbox', { name: '中文译文', exact: true })).toHaveValue('草稿中的中文')
  await languages.getByRole('button', { name: '原文', exact: true }).click()

  await card.getByRole('textbox', { name: 'X 简介原文', exact: true }).fill('未保存的X简介')
  await card.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('alertdialog')).toBeVisible()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(card.getByRole('textbox', { name: 'X 简介原文', exact: true })).toHaveValue('未保存的X简介')
  await card.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(card.getByRole('textbox')).toHaveCount(0)
  await card.getByRole('button', { name: '编辑账号来源', exact: true }).click()
  await expect(card.getByRole('textbox')).toHaveCount(2)
  await expect(card.getByLabel('自动检查公开资料', { exact: true })).toBeDisabled()
  await card.getByRole('button', { name: '取消', exact: true }).click()
  await card.getByRole('button', { name: '编辑作品订阅', exact: true }).click()
  await expect(card.getByRole('textbox')).toHaveCount(1)
  await expect(card.getByLabel('检查作品订阅更新', { exact: true })).toBeDisabled()
  await card.getByRole('button', { name: '取消', exact: true }).click()
  await card.getByRole('button', { name: '填入候选草稿，继续核对' }).click()
  expect(writes).toHaveLength(0)
  await expect(card.getByRole('textbox')).toHaveCount(5)
  await expect(card.getByRole('textbox', { name: '整理简介', exact: true })).toHaveValue('原人工简介')
  await page.screenshot({ path: testInfo.outputPath('creator-candidate-editor.png'), fullPage: true })
  await card.getByRole('button', { name: '保存资料', exact: true }).click()
  await expect(card.getByText(/保存失败，草稿已保留/)).toBeVisible()
  await expect(card.getByRole('textbox', { name: '整理简介', exact: true })).toHaveValue('原人工简介')
  expect(writes[0]).toMatchObject({ revision: 3, biography: '原人工简介', status: 'candidate', evidenceUrls: ['https://example.org/manual', 'https://example.org/about'], xProfile: profile.xProfile })
  fail = false
  await card.getByRole('button', { name: '保存资料', exact: true }).click()
  await expect(card.getByRole('textbox')).toHaveCount(0)
  await expect(card.getByRole('button', { name: '编辑X资料', exact: true })).toBeVisible()
  expect(writes).toHaveLength(2)
  await card.getByRole('button', { name: '编辑X资料', exact: true }).click()
  await expect(card.getByRole('textbox', { name: 'X 简介原文', exact: true })).toHaveValue(profile.xProfile.biography)
  await card.getByRole('textbox', { name: 'X 简介原文', exact: true }).fill('更新后的X简介')
  await card.getByRole('button', { name: '保存资料', exact: true }).click()
  await expect(card.getByRole('textbox')).toHaveCount(0)
  expect(writes).toHaveLength(3)
  expect(writes[2]).toMatchObject({ nickname: '公开昵称', biography: '原人工简介', homepageUrl: profile.homepageUrl, evidenceUrls: ['https://example.org/manual', 'https://example.org/about'], xProfile: { url: profile.xProfile.url, biography: '更新后的X简介', biographyZh: '' } })
})
