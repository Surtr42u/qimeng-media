import { describe, expect, it } from 'vitest'
import type { CustomSourceGroups } from '@/api/generated'
import {
  addAlias,
  addCharacter,
  addGroup,
  addStopWord,
  addVariant,
  payloadToDraft,
  removeAlias,
  removeCharacter,
  removeGroup,
  removeStopWord,
  removeVariant,
  renameCharacter,
  renameGroup,
  runeLength,
  toPayload,
  truncateToRuneMax,
  type VocabularyDraft,
} from './vocabulary-edit'

/** no-op 返回 null，成功返回新副本：用例里断言非空后取值 */
function must<T>(value: T | null): T {
  if (value === null) throw new Error('操作意外 no-op（返回 null）')
  return value
}

/** App 端 VocabularyEditViewModelTest 的 web 对位（纯函数面；「置脏」在页面层 = 非 null 返回） */
describe('payloadToDraft', () => {
  it('可省 stopWords 归一空数组', () => {
    expect(payloadToDraft({ groups: [] }).stopWords).toEqual([])
  })

  it('GET 载荷直取为编辑副本（无映射）', () => {
    const payload: CustomSourceGroups = {
      groups: [{ canonical: '守望先锋', variants: ['OW'], characters: [{ canonical: 'D.Va', aliases: ['diva'] }] }],
      stopWords: ['触手'],
    }
    expect(payloadToDraft(payload)).toEqual({
      groups: [{ canonical: '守望先锋', variants: ['OW'], characters: [{ canonical: 'D.Va', aliases: ['diva'] }] }],
      stopWords: ['触手'],
    })
  })
})

describe('五层编辑操作', () => {
  const base: VocabularyDraft = {
    groups: [
      {
        canonical: '守望先锋',
        variants: ['OW'],
        characters: [{ canonical: 'D.Va', aliases: ['diva'] }],
      },
    ],
    stopWords: ['触手'],
  }

  it('新增：trim 生效，空白静默 no-op', () => {
    expect(must(addGroup(base, '  最终幻想  ')).groups.at(-1)?.canonical).toBe('最终幻想')
    expect(addGroup(base, '   ')).toBeNull()
    expect(addGroup(base, '\t\n')).toBeNull()
  })

  it('组改名/移除', () => {
    expect(must(renameGroup(base, 0, ' OW ')).groups[0]?.canonical).toBe('OW')
    expect(renameGroup(base, 0, ' ')).toBeNull()
    expect(must(removeGroup(base, 0)).groups).toEqual([])
  })

  it('变体增删：删到空表归一 undefined（协议可省字段）', () => {
    const added = must(addVariant(base, 0, ' overwatch '))
    expect(added.groups[0]?.variants).toEqual(['OW', 'overwatch'])
    const removed = must(removeVariant(added, 0, 1))
    expect(removed.groups[0]?.variants).toEqual(['OW'])
    expect(must(removeVariant(removed, 0, 0)).groups[0]?.variants).toBeUndefined()
  })

  it('角色增删改', () => {
    expect(must(addCharacter(base, 0, '天使')).groups[0]?.characters?.length).toBe(2)
    expect(must(renameCharacter(base, 0, 0, ' dva ')).groups[0]?.characters?.[0]?.canonical).toBe('dva')
    // 删到唯一角色 → characters 空表归一 undefined
    const oneChar: VocabularyDraft = { ...base, groups: [{ canonical: '守望先锋', characters: [{ canonical: 'D.Va' }] }] }
    expect(must(removeCharacter(oneChar, 0, 0)).groups[0]?.characters).toBeUndefined()
  })

  it('别名增删：空表归一 undefined', () => {
    const added = must(addAlias(base, 0, 0, ' DV5 '))
    expect(added.groups[0]?.characters?.[0]?.aliases).toEqual(['diva', 'DV5'])
    const only: VocabularyDraft = {
      ...base,
      groups: [{ canonical: '守望先锋', characters: [{ canonical: 'D.Va', aliases: ['diva'] }] }],
    }
    expect(must(removeAlias(only, 0, 0, 0)).groups[0]?.characters?.[0]?.aliases).toBeUndefined()
  })

  it('停用词增删', () => {
    expect(must(addStopWord(base, ' 白丝 ')).stopWords).toEqual(['触手', '白丝'])
    expect(must(removeStopWord(base, 0)).stopWords).toEqual([])
  })
})

describe('越界静默（非法下标 no-op）', () => {
  const base: VocabularyDraft = { groups: [{ canonical: 'A' }], stopWords: [] }

  it('组级越界', () => {
    expect(removeGroup(base, 1)).toBeNull()
    expect(removeGroup(base, -1)).toBeNull()
    expect(renameGroup(base, 9, 'x')).toBeNull()
    expect(addVariant(base, 3, 'v')).toBeNull()
  })

  it('条目级越界', () => {
    expect(removeVariant(base, 0, 0)).toBeNull()
    expect(addCharacter(base, 5, 'c')).toBeNull()
    expect(removeCharacter(base, 0, 0)).toBeNull()
    expect(renameCharacter(base, 0, 0, 'c')).toBeNull()
    expect(addAlias(base, 0, 0, 'a')).toBeNull()
    expect(removeAlias(base, 0, 0, 0)).toBeNull()
    expect(removeStopWord(base, 0)).toBeNull()
  })
})

describe('toPayload（整体替换语义）', () => {
  it('stopWords 恒显式携带：空数组也传（显式空=清空回内置基线，不是缺省=保持）', () => {
    expect(toPayload({ groups: [], stopWords: [] }).stopWords).toEqual([])
  })

  it('空表归一的可省列表不出现在载荷键上', () => {
    const draft = must(
      removeVariant(
        { groups: [{ canonical: 'A', variants: ['v'] }], stopWords: [] },
        0,
        0,
      ),
    )
    expect(toPayload(draft).groups[0]?.variants).toBeUndefined()
  })
})

describe('rune 计数（openapi maxLength 语义）', () => {
  it('增补面字符按 1 个 rune 计（String.length 会虚高）', () => {
    expect(runeLength('D.Va')).toBe(4)
    expect(runeLength('🎮🎮')).toBe(2)
    expect('🎮🎮'.length).toBe(4)
  })

  it('按 rune 截断不产生半个代理对', () => {
    expect(truncateToRuneMax('🎮a🎮b', 3)).toBe('🎮a🎮')
  })
})
