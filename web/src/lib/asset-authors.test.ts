import { describe, expect, it } from 'vitest'
import type { Author } from '@/api/generated'
import {
  addPendingAuthorName,
  authorDraftsFromDetail,
  authorIdentityKey,
  authorNameLength,
  authorsReplaceBody,
  NEW_AUTHOR_NAME_MAX_LEN,
  NEW_AUTHOR_NAMES_MAX_ITEMS,
  pendingAuthorRejectionText,
  removePendingAuthorName,
  unmatchedCreateName,
} from './asset-authors'

describe('unmatchedCreateName（联想未命中的「新建」入口判定）', () => {
  it('无任何联想项时返回 trim 后的输入名', () => {
    expect(unmatchedCreateName([], '  新作者甲  ')).toBe('新作者甲')
  })

  it('与联想项大小写/空白差异命中时不给新建入口（同一规范身份）', () => {
    expect(unmatchedCreateName(['zhangsan'], 'Zhang San')).toBeNull()
    expect(unmatchedCreateName(['Zhang San'], 'zhangsan')).toBeNull()
  })

  it('仅子串命中不算命中——仍给新建入口', () => {
    expect(unmatchedCreateName(['张三丰'], '张三')).toBe('张三')
  })

  it('空输入/纯空白无新建入口', () => {
    expect(unmatchedCreateName([], '')).toBeNull()
    expect(unmatchedCreateName([], '   ')).toBeNull()
  })
})

describe('authorIdentityKey / authorNameLength（规范身份与 rune 口径）', () => {
  it('规范身份键 = trim + 小写 + 去全部空白', () => {
    expect(authorIdentityKey(' Zhang  San ')).toBe('zhangsan')
    expect(authorIdentityKey('张三')).toBe('张三')
  })

  it('长度按 Unicode 码点计（代理对算 1，与 Go RuneCountInString 同口径）', () => {
    expect(authorNameLength('😀')).toBe(1)
    expect(authorNameLength('张三')).toBe(2)
    expect('😀'.length).toBe(2) // 对照：UTF-16 长度会把代理对算 2
  })
})

describe('addPendingAuthorName（待新建名入列：trim 空忽略/去重/超长/超项）', () => {
  const empty = { attached: [], pending: [] }

  it('trim 后入列，原文即显示名', () => {
    const r = addPendingAuthorName(empty, '  新作者甲  ')
    expect(r).toEqual({ ok: true, names: ['新作者甲'] })
  })

  it('trim 后为空忽略', () => {
    expect(addPendingAuthorName(empty, '   ')).toEqual({ ok: false, reason: 'empty' })
  })

  it('与已关联作者忽略大小写/空白去重', () => {
    const state = { attached: ['Zhang San'], pending: [] }
    expect(addPendingAuthorName(state, 'zhangsan')).toEqual({
      ok: false,
      reason: 'duplicate-attached',
    })
  })

  it('待新建清单内忽略大小写/空白去重', () => {
    const state = { attached: [], pending: ['New Author'] }
    expect(addPendingAuthorName(state, 'newauthor')).toEqual({
      ok: false,
      reason: 'duplicate-pending',
    })
  })

  it('不改动原数组（不可变更新）', () => {
    const pending = ['甲']
    const r = addPendingAuthorName({ attached: [], pending }, '乙')
    expect(r).toEqual({ ok: true, names: ['甲', '乙'] })
    expect(pending).toEqual(['甲'])
  })

  it('200 字符（含）可入列，201 字符明确拒绝', () => {
    const okLen = '李'.repeat(NEW_AUTHOR_NAME_MAX_LEN)
    expect(addPendingAuthorName(empty, okLen)).toEqual({ ok: true, names: [okLen] })
    const overLen = '李'.repeat(NEW_AUTHOR_NAME_MAX_LEN + 1)
    expect(addPendingAuthorName(empty, overLen)).toEqual({ ok: false, reason: 'too-long' })
  })

  it('代理对按码点计长：200 个 emoji 可入列（UTF-16 长度 400 不误杀）', () => {
    const emoji = '😀'.repeat(NEW_AUTHOR_NAME_MAX_LEN)
    expect(addPendingAuthorName(empty, emoji)).toEqual({ ok: true, names: [emoji] })
  })

  it(`超过 ${NEW_AUTHOR_NAMES_MAX_ITEMS} 项拒绝入列`, () => {
    let pending: string[] = []
    for (let i = 0; i < NEW_AUTHOR_NAMES_MAX_ITEMS; i++) {
      const r = addPendingAuthorName({ attached: [], pending }, `作者${i}`)
      expect(r.ok).toBe(true)
      if (r.ok) pending = r.names
    }
    expect(pending).toHaveLength(NEW_AUTHOR_NAMES_MAX_ITEMS)
    expect(addPendingAuthorName({ attached: [], pending }, '作者超限')).toEqual({
      ok: false,
      reason: 'too-many',
    })
  })
})

describe('removePendingAuthorName（待新建清单移除）', () => {
  it('按规范身份键移除，其余保序', () => {
    expect(removePendingAuthorName(['甲', 'New Author', '乙'], 'newauthor')).toEqual(['甲', '乙'])
  })

  it('不存在的名字原样返回', () => {
    expect(removePendingAuthorName(['甲'], '丙')).toEqual(['甲'])
  })
})

describe('pendingAuthorRejectionText（拒绝原因文案）', () => {
  it('超长处文案含上限值', () => {
    expect(pendingAuthorRejectionText('too-long', 'x')).toContain(String(NEW_AUTHOR_NAME_MAX_LEN))
  })

  it('重名文案含名字原文（trim 后）', () => {
    expect(pendingAuthorRejectionText('duplicate-attached', ' 甲 ')).toContain('「甲」')
    expect(pendingAuthorRejectionText('duplicate-pending', '甲')).toContain('「甲」')
  })

  it('五种原因都有非空文案', () => {
    const reasons = ['empty', 'too-long', 'too-many', 'duplicate-attached', 'duplicate-pending'] as const
    for (const reason of reasons) expect(pendingAuthorRejectionText(reason, '甲')).not.toBe('')
  })
})

describe('authorsReplaceBody（保存 payload 组装）', () => {
  it('待新建名进 newAuthorNames，与 authorIds 并列', () => {
    const body = authorsReplaceBody(['a1', 'a2'], ['新作者甲', '新作者乙'])
    expect(body.authorIds).toEqual(['a1', 'a2'])
    expect(body.newAuthorNames).toEqual(['新作者甲', '新作者乙'])
  })

  it('无待新建也显式带上空数组（与协议缺省等价，调用点可见）', () => {
    expect(authorsReplaceBody(['a1'], [])).toEqual({ authorIds: ['a1'], newAuthorNames: [] })
  })

  it('解除全部关联：两个字段都空', () => {
    expect(authorsReplaceBody([], [])).toEqual({ authorIds: [], newAuthorNames: [] })
  })

  it('不改动入参数组（拷贝）', () => {
    const authorIds = ['a1']
    const names = ['甲']
    authorsReplaceBody(authorIds, names)
    expect(authorIds).toEqual(['a1'])
    expect(names).toEqual(['甲'])
  })
})

describe('authorDraftsFromDetail（编辑基线作者行提取）', () => {
  it('过滤 COS 作者（type=cos 与 cos_ 前缀两种形态）', () => {
    const authors: Author[] = [
      { id: 'a1', displayName: '甲', type: 'regular' },
      { id: 'cos_1', displayName: 'COS 作者', type: 'cos' },
      { id: 'cos_2', displayName: 'COS 前缀作者', type: 'regular' },
      { id: 'a2', displayName: '乙' },
    ]
    expect(authorDraftsFromDetail(authors)).toEqual([
      { id: 'a1', displayName: '甲' },
      { id: 'a2', displayName: '乙' },
    ])
  })

  it('displayName 缺失回退 id；缺 id/空 id 行丢弃', () => {
    const authors: Author[] = [{ id: 'a1' }, { id: '' }, { displayName: '无 id' }]
    expect(authorDraftsFromDetail(authors)).toEqual([{ id: 'a1', displayName: 'a1' }])
  })

  it('detail 未到货（undefined）返回空数组', () => {
    expect(authorDraftsFromDetail(undefined)).toEqual([])
  })
})
