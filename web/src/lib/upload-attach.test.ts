/**
 * 上传成功后挂靠服务锁定（node 环境，2026-10-24 Q7 职责提取批自
 * upload-queue-store 拆出后的行为回归）：无挂靠载荷/空作者 id 直通 done、
 * 响应缺资产 id 落「已入库待挂靠」、挂靠序列先 authors 整体替换后 sources
 * append（顺序与载荷逐字不变）、sources 空数组不发起 sources 请求、任何挂靠
 * 失败不重试不回滚——一律转 attach-failed 专项态并透传失败原因（文件本体已
 * 入库，绝不改判 failed/重传）。断言口径与提取前 store 内实现一致（行为不变，
 * 仅落位新文件）；store 级集成（enqueue→201→挂靠→终态）仍由
 * upload-chunked.test.ts 全链用例覆盖。生成 SDK 函数打桩不真出网（假件约定：
 * fake 不 mock 传输，仅桩 SDK 边界）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

// api-client.ts 在模块顶层读 localStorage（token 缓存，浏览器专用全局），
// node 测试环境无此全局——先打桩再动态 import（upload-queue-store.test.ts
// 同款前置；挂靠服务经 unwrapSdkResult 传递依赖 api-client）。
vi.stubGlobal('localStorage', {
  getItem: (): string | null => null,
  setItem: () => {},
  removeItem: () => {},
})

// 挂靠序列走生成 SDK：打桩记录调用，不真出网（upload-chunked.test.ts 同款）
vi.mock('@/api/generated', () => ({
  putApiV1AssetsByAssetIdAuthors: vi.fn(async () => ({ data: {} })),
  putApiV1AuthorsByAuthorIdSources: vi.fn(async () => ({ data: {} })),
}))

const { postUploadOutcome, parseUploadedAssetId } = await import('./upload-attach')
const generated = await import('@/api/generated')

const putAssetAuthors = vi.mocked(generated.putApiV1AssetsByAssetIdAuthors)
const putAuthorSources = vi.mocked(generated.putApiV1AuthorsByAuthorIdSources)

/** 挂靠载荷替身（服务只消费 authorId/sources；authorName 纯展示） */
const attach = (overrides: Partial<{ authorId: string; sources: string[] }> = {}) => ({
  authorId: 'author-1',
  authorName: '作者甲',
  sources: ['网盘'],
  ...overrides,
})

beforeEach(() => {
  vi.clearAllMocks() // 清调用记录；打桩实现保留（失败用例用 Once 覆写单次）
})

describe('parseUploadedAssetId', () => {
  it('合法 201 体取 id；非 JSON / 缺 id / 空串 id / 非字符串 id 均 null', () => {
    expect(parseUploadedAssetId('{"id":"asset-1","fileName":"a.mp4"}')).toBe('asset-1')
    expect(parseUploadedAssetId('not-json')).toBeNull()
    expect(parseUploadedAssetId('{"fileName":"a.mp4"}')).toBeNull()
    expect(parseUploadedAssetId('{"id":""}')).toBeNull()
    expect(parseUploadedAssetId('{"id":123}')).toBeNull()
  })
})

describe('postUploadOutcome 无挂靠载荷', () => {
  it('undefined / 空 authorId 均直通 done，零 SDK 出网', async () => {
    await expect(postUploadOutcome('{"id":"asset-1"}', undefined)).resolves.toEqual({
      status: 'done',
      percent: 100,
    })
    // 防御分支：载荷存在但作者 id 为空（UI 侧不应构造，行为保持直通不挂靠）
    await expect(postUploadOutcome('{"id":"asset-1"}', attach({ authorId: '' }))).resolves.toEqual({
      status: 'done',
      percent: 100,
    })
    expect(putAssetAuthors).not.toHaveBeenCalled()
    expect(putAuthorSources).not.toHaveBeenCalled()
  })
})

describe('postUploadOutcome 响应缺资产 id', () => {
  it.each(['not-json', '{"fileName":"a.mp4"}', '{"id":""}'])(
    'body=%s → attach-failed 固定文案，零 SDK 出网',
    async (body) => {
      await expect(postUploadOutcome(body, attach())).resolves.toEqual({
        status: 'attach-failed',
        percent: 100,
        errorText: '文件已入库，但响应缺少资产 ID，未能自动挂靠',
      })
      expect(putAssetAuthors).not.toHaveBeenCalled()
    },
  )
})

describe('postUploadOutcome 挂靠序列成功', () => {
  it('sources 非空：先 authors 单项整体替换、后 sources append，全部挂上 → done', async () => {
    await expect(
      postUploadOutcome('{"id":"asset-9"}', attach({ sources: ['网盘', '论坛'] })),
    ).resolves.toEqual({ status: 'done', percent: 100 })
    expect(putAssetAuthors).toHaveBeenCalledWith({
      path: { assetId: 'asset-9' },
      body: { authorIds: ['author-1'] },
    })
    expect(putAuthorSources).toHaveBeenCalledWith({
      path: { authorId: 'author-1' },
      body: { sources: ['网盘', '论坛'], mode: 'append' },
    })
  })

  it('sources 空数组 = 不动来源：只发起 authors 替换，不调 sources', async () => {
    await expect(
      postUploadOutcome('{"id":"asset-9"}', attach({ sources: [] })),
    ).resolves.toEqual({ status: 'done', percent: 100 })
    expect(putAssetAuthors).toHaveBeenCalledTimes(1)
    expect(putAuthorSources).not.toHaveBeenCalled()
  })
})

describe('postUploadOutcome 挂靠失败转「已入库待挂靠」', () => {
  it('authors 替换失败：attach-failed 透传原因，sources 不再发起（顺序固定短路）', async () => {
    putAssetAuthors.mockRejectedValueOnce({ code: 'AUTHOR_NOT_FOUND', message: '作者不存在', status: 404 })

    await expect(postUploadOutcome('{"id":"asset-9"}', attach())).resolves.toEqual({
      status: 'attach-failed',
      percent: 100,
      errorText: '文件已入库，但挂靠失败：作者不存在',
    })
    expect(putAuthorSources).not.toHaveBeenCalled() // sources 依赖作者块，前置失败即止步
  })

  it('sources append 失败：同款 attach-failed 文案（authors 已挂不回滚）', async () => {
    putAuthorSources.mockRejectedValueOnce({ code: 'SOURCE_INVALID', message: '来源词不合法', status: 400 })

    await expect(postUploadOutcome('{"id":"asset-9"}', attach())).resolves.toEqual({
      status: 'attach-failed',
      percent: 100,
      errorText: '文件已入库，但挂靠失败：来源词不合法',
    })
    expect(putAssetAuthors).toHaveBeenCalledTimes(1)
  })

  it('SDK 错误体无 message：走 batchFailureReason 状态码兜底文案', async () => {
    putAssetAuthors.mockRejectedValueOnce({ status: 500 })

    const outcome = await postUploadOutcome('{"id":"asset-9"}', attach())
    expect(outcome.status).toBe('attach-failed')
    expect(outcome.errorText).toBe('文件已入库，但挂靠失败：请求失败（HTTP 500）')
  })
})
