import { describe, expect, it } from 'vitest'
import {
  ASSETS_LIST_QUERY_KEY,
  ASSETS_QUERY_KEY,
  AUTHORS_QUERY_KEY,
  DIRS_QUERY_KEY,
  HISTORY_QUERY_KEY,
  LIBRARIES_QUERY_KEY,
  RANKINGS_QUERY_KEY,
  RECOMMENDATIONS_QUERY_KEY,
  SEARCH_SUGGESTIONS_QUERY_KEY,
  SOURCES_QUERY_KEY,
  STATS_QUERY_KEY,
  TAGS_QUERY_KEY,
  TRASH_QUERY_KEY,
  TXT_FILES_QUERY_KEY,
} from './query-keys'

/**
 * TanStack Query 前缀匹配是「逐元素相等」而非字符串前缀：子键必须以
 * [...父键, ...] 构造，否则 invalidateQueries(父键) 落空（历史「死键」根因，
 * 见 query-keys.ts 头注与 2026-09-05/06 审查）。本文件锁定该形态契约。
 */
describe('query-keys 前缀契约', () => {
  it('资产列表键以资产族根键开头', () => {
    expect(ASSETS_LIST_QUERY_KEY.slice(0, ASSETS_QUERY_KEY.length)).toEqual(ASSETS_QUERY_KEY)
  })

  it('作者 TXT 片段键以作者族根键开头', () => {
    expect(TXT_FILES_QUERY_KEY.slice(0, AUTHORS_QUERY_KEY.length)).toEqual(AUTHORS_QUERY_KEY)
  })

  it('各族根键首段为协议路径形态 api/v1/…（与 openapi 路径对齐）', () => {
    const roots = [
      ASSETS_QUERY_KEY,
      LIBRARIES_QUERY_KEY,
      DIRS_QUERY_KEY,
      TAGS_QUERY_KEY,
      AUTHORS_QUERY_KEY,
      SEARCH_SUGGESTIONS_QUERY_KEY,
      TRASH_QUERY_KEY,
      RECOMMENDATIONS_QUERY_KEY,
      SOURCES_QUERY_KEY,
      STATS_QUERY_KEY,
      RANKINGS_QUERY_KEY,
      HISTORY_QUERY_KEY,
    ]
    for (const key of roots) {
      expect(key[0]).toMatch(/^api\/v1\//)
    }
  })

  it('资产族根键字面量锁定（SSE 桥与 hooks 共用同一常量的防手抄锚点）', () => {
    expect(ASSETS_QUERY_KEY).toEqual(['api/v1/assets'])
  })
})
