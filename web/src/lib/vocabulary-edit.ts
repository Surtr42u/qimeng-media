import type {
  CustomSourceCharacter,
  CustomSourceGroup,
  CustomSourceGroups,
} from '@/api/generated'

/**
 * 词表维护页编辑状态机（App ADR-0035「词表维护」的 web 移植，2026-10-04）：
 * 全部操作是纯函数（App 端 VocabularyEditViewModel 的编辑操作去 IO 化），页面只
 * 持状态与转发——去空/去重是服务端 PUT 规范化职责（保存后回读抹平展示差异），
 * 这里只拦空白与越界（返回 null = no-op 不置脏）。编辑模型直接用 SDK 生成物
 * （协议即编辑形态，无映射层；可省列表空表归一为 undefined）。
 */

/**
 * 协议容量投影（api/openapi.yaml CustomSourceGroups 族 maxItems/maxLength，
 * ADR-0033 定；与 App 端 VocabularyLimits 同源同值）。输入框与「新增/添加」按钮
 * 按此封顶，超限会被服务端 400 拒收——协议侧改动须同步此处，反之亦然。
 * TEXT_MAX 按 rune（码点）计，与 openapi maxLength 语义一致（String.length 是
 * UTF-16 单元，emoji 等增补面字符会虚高）。
 */
export const VOCABULARY_LIMITS = {
  /** 单串上限（canonical/变体/角色名/别名/停用词同档） */
  TEXT_MAX: 100,
  /** 出处组数上限 */
  GROUPS_MAX: 256,
  /** 单组变体数上限 */
  VARIANTS_MAX: 64,
  /** 单组角色数上限 */
  CHARACTERS_MAX: 128,
  /** 单角色别名数上限 */
  ALIASES_MAX: 32,
  /** 停用词数上限 */
  STOP_WORDS_MAX: 256,
} as const

/** 内存编辑副本（GET 载荷的直取形态；stopWords 恒为数组，空=清空回内置基线） */
export interface VocabularyDraft {
  groups: CustomSourceGroup[]
  stopWords: string[]
}

/** GET 载荷 → 编辑副本（可省 stopWords 归一空数组） */
export function payloadToDraft(payload: CustomSourceGroups): VocabularyDraft {
  return { groups: payload.groups, stopWords: payload.stopWords ?? [] }
}

/** rune 计数（VOCABULARY_LIMITS.TEXT_MAX 的计数口径） */
export function runeLength(text: string): number {
  return Array.from(text).length
}

/** 按 rune 截断（输入框封顶；slice(0, n) 会截半个增补面字符） */
export function truncateToRuneMax(text: string, max: number): string {
  return Array.from(text).slice(0, max).join('')
}

/** trim 后非空才进 action；空白输入静默 no-op（null；新增/改名同口径） */
function meaningful(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

// ---- 出处组层 ----

export function addGroup(draft: VocabularyDraft, canonical: string): VocabularyDraft | null {
  const trimmed = meaningful(canonical)
  if (trimmed === null) return null
  return { ...draft, groups: [...draft.groups, { canonical: trimmed }] }
}

export function removeGroup(draft: VocabularyDraft, index: number): VocabularyDraft | null {
  if (!inRange(index, draft.groups)) return null
  return { ...draft, groups: draft.groups.toSpliced(index, 1) }
}

export function renameGroup(
  draft: VocabularyDraft,
  index: number,
  canonical: string,
): VocabularyDraft | null {
  const trimmed = meaningful(canonical)
  if (trimmed === null) return null
  return withGroup(draft, index, (group) => ({ ...group, canonical: trimmed }))
}

// ---- 变体层 ----

export function addVariant(
  draft: VocabularyDraft,
  groupIndex: number,
  variant: string,
): VocabularyDraft | null {
  const trimmed = meaningful(variant)
  if (trimmed === null) return null
  return withGroup(draft, groupIndex, (group) => ({
    ...group,
    variants: [...(group.variants ?? []), trimmed],
  }))
}

export function removeVariant(
  draft: VocabularyDraft,
  groupIndex: number,
  variantIndex: number,
): VocabularyDraft | null {
  return withGroup(draft, groupIndex, (group) => {
    const variants = listOrEmpty(group.variants)
    if (!inRange(variantIndex, variants)) return null
    return { ...group, variants: shrinkList(variants.toSpliced(variantIndex, 1)) }
  })
}

// ---- 角色层 ----

export function addCharacter(
  draft: VocabularyDraft,
  groupIndex: number,
  canonical: string,
): VocabularyDraft | null {
  const trimmed = meaningful(canonical)
  if (trimmed === null) return null
  return withGroup(draft, groupIndex, (group) => ({
    ...group,
    characters: [...(group.characters ?? []), { canonical: trimmed }],
  }))
}

export function removeCharacter(
  draft: VocabularyDraft,
  groupIndex: number,
  characterIndex: number,
): VocabularyDraft | null {
  return withGroup(draft, groupIndex, (group) => {
    const characters = listOrEmpty(group.characters)
    if (!inRange(characterIndex, characters)) return null
    return {
      ...group,
      characters: shrinkList(
        characters.toSpliced(characterIndex, 1).map((character) => ({
          ...character,
          aliases: shrinkList(character.aliases ?? []),
        })),
      ),
    }
  })
}

export function renameCharacter(
  draft: VocabularyDraft,
  groupIndex: number,
  characterIndex: number,
  canonical: string,
): VocabularyDraft | null {
  const trimmed = meaningful(canonical)
  if (trimmed === null) return null
  return withCharacter(draft, groupIndex, characterIndex, (character) => ({
    ...character,
    canonical: trimmed,
  }))
}

// ---- 别名层 ----

export function addAlias(
  draft: VocabularyDraft,
  groupIndex: number,
  characterIndex: number,
  alias: string,
): VocabularyDraft | null {
  const trimmed = meaningful(alias)
  if (trimmed === null) return null
  return withCharacter(draft, groupIndex, characterIndex, (character) => ({
    ...character,
    aliases: [...(character.aliases ?? []), trimmed],
  }))
}

export function removeAlias(
  draft: VocabularyDraft,
  groupIndex: number,
  characterIndex: number,
  aliasIndex: number,
): VocabularyDraft | null {
  return withCharacter(draft, groupIndex, characterIndex, (character) => {
    const aliases = listOrEmpty(character.aliases)
    if (!inRange(aliasIndex, aliases)) return null
    return { ...character, aliases: shrinkList(aliases.toSpliced(aliasIndex, 1)) }
  })
}

// ---- 停用词层 ----

export function addStopWord(draft: VocabularyDraft, word: string): VocabularyDraft | null {
  const trimmed = meaningful(word)
  if (trimmed === null) return null
  return { ...draft, stopWords: [...draft.stopWords, trimmed] }
}

export function removeStopWord(draft: VocabularyDraft, index: number): VocabularyDraft | null {
  if (!inRange(index, draft.stopWords)) return null
  return { ...draft, stopWords: draft.stopWords.toSpliced(index, 1) }
}

/**
 * 编辑副本 → PUT 载荷（整体替换语义 = 用户看到什么就保存什么）。
 * stopWords 恒显式携带（协议：缺省=保持现值、空数组=清空——「所见即所存」必须
 * 显式传空才能清空）；组内可省列表在编辑期已空表归一 undefined，此处不再动。
 */
export function toPayload(draft: VocabularyDraft): CustomSourceGroups {
  return { groups: draft.groups, stopWords: draft.stopWords }
}

// ---- 内部工具 ----

function inRange(index: number, list: readonly unknown[]): boolean {
  return Number.isInteger(index) && index >= 0 && index < list.length
}

/** 可省列表归一（协议「可省」字段空表回 undefined，与 App removeAtOrNull 空表回 null 同义） */
function listOrEmpty<T>(list: readonly T[] | undefined): T[] {
  return list === undefined ? [] : [...list]
}

function shrinkList<T>(list: T[]): T[] | undefined {
  return list.length === 0 ? undefined : list
}

function withGroup(
  draft: VocabularyDraft,
  index: number,
  transform: (group: CustomSourceGroup) => CustomSourceGroup | null,
): VocabularyDraft | null {
  if (!inRange(index, draft.groups)) return null
  const next = transform(draft.groups[index])
  if (next === null) return null
  return { ...draft, groups: draft.groups.with(index, next) }
}

function withCharacter(
  draft: VocabularyDraft,
  groupIndex: number,
  characterIndex: number,
  transform: (character: CustomSourceCharacter) => CustomSourceCharacter | null,
): VocabularyDraft | null {
  return withGroup(draft, groupIndex, (group) => {
    const characters = listOrEmpty(group.characters)
    if (!inRange(characterIndex, characters)) return null
    const next = transform(characters[characterIndex])
    if (next === null) return null
    return { ...group, characters: characters.with(characterIndex, next) }
  })
}
