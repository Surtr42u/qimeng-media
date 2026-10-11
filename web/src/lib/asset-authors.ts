/**
 * 资产编辑页作者草稿纯逻辑（铁律 7：规则落 lib 纯函数、单测锁定，组件/页面只做
 * 渲染与编排）。
 *
 * 两块口径（2026-10-11 协议批「编辑页支持新建作者」，协议 =
 * PUT /assets/{assetId}/authors 的 newAuthorNames，api/openapi.yaml）：
 * 1. 草稿作者行 = 服务端 AssetDetail.authors 过滤 COS 作者后的 {id, displayName}；
 *    保存提交 ID 全集。COS 作者由 COS 目录语义决定、协议拒绝其 ID，编辑面不收。
 * 2. 待新建作者名 = 联想未命中的输入名，随保存进 newAuthorNames，由服务端按
 *    「规范身份」复用已有作者或就地新建；本地只拦可预判的四种情形（空/超长/
 *    与已关联或待新建重名/超项数），保证不产出必 400 的载荷。
 *
 * 服务端校验口径（同 openapi）：单名超 200 rune、超 20 项、非法形态（控制字符/
 * 纯数字/带媒体扩展名/编号行形态）一律 400——非法形态不在前端重复实现（前端
 * 猜不全），由保存失败文案回给用户，待新建清单保留可改可重试。
 */

import type { AssetAuthorsReplaceRequest, Author } from '@/api/generated'

/** 草稿作者行（id 提交协议；displayName 仅前端展示，缺省回退 id） */
export interface AuthorDraft {
  id: string
  displayName: string
}

/** 待新建作者名单名长度上限（与 openapi newAuthorNames.items.maxLength=200 双写，改动须同步） */
export const NEW_AUTHOR_NAME_MAX_LEN = 200

/** 待新建作者名单次项数上限（与 openapi newAuthorNames.maxItems=20 双写，改动须同步） */
export const NEW_AUTHOR_NAMES_MAX_ITEMS = 20

/**
 * 规范身份键：trim + 去大小写 + 去全部空白。前端近似服务端「规范身份」（trim +
 * 忽略大小写/空白差异；别名拆分归一服务端独有、前端不可知）——用于「已关联/
 * 待新建」去重与联想精确命中判定，避免把服务端会合并成一个身份的写法当两个。
 */
export function authorIdentityKey(name: string): string {
  return name.trim().toLowerCase().replace(/\s+/g, '')
}

/**
 * 名字长度按 Unicode 码点计（Go utf8.RuneCountInString 同口径——服务端 maxLength
 * 按 rune 判；String.length 会把代理对（如 emoji）算 2，会错杀/漏拦）。
 */
export function authorNameLength(name: string): number {
  return [...name].length
}

/** 草稿作者行提取：常规作者（有 id、非 COS）映射为 {id, displayName}；COS 作者不入编辑面 */
export function authorDraftsFromDetail(authors: readonly Author[] | undefined): AuthorDraft[] {
  return (authors ?? [])
    .filter(
      (a): a is Author & { id: string } =>
        a.id != null && a.id !== '' && a.type !== 'cos' && !a.id.startsWith('cos_'),
    )
    .map((a) => ({ id: a.id, displayName: a.displayName ?? a.id }))
}

/**
 * 联想未命中时的「新建」名：输入 trim 后非空且与任何联想名不构成同一规范身份时
 * 返回该名（组件据此给出「新建：<name>」入口）；空输入或已命中返回 null。
 */
export function unmatchedCreateName(suggestionNames: readonly string[], input: string): string | null {
  const name = input.trim()
  if (name === '') return null
  const key = authorIdentityKey(name)
  return suggestionNames.some((s) => authorIdentityKey(s) === key) ? null : name
}

/** 加入待新建被拒的原因（空/超长/重名/超项） */
export type PendingAuthorRejection =
  | 'empty'
  | 'too-long'
  | 'too-many'
  | 'duplicate-attached'
  | 'duplicate-pending'

/** 加入待新建结果：ok=新清单（不可变更新）；否则原因码交调用方出提示 */
export type PendingAuthorAddResult =
  | { ok: true; names: string[] }
  | { ok: false; reason: PendingAuthorRejection }

/**
 * 加入一个待新建名：trim 空忽略；超 200 字符、与已关联作者忽略大小写/空白重名、
 * 与待新建清单内重名、超过 20 项分别拒绝。名以 trim 后原文入列（服务端按名建行，
 * 原文即显示名）。
 */
export function addPendingAuthorName(
  state: { attached: readonly string[]; pending: readonly string[] },
  raw: string,
): PendingAuthorAddResult {
  const name = raw.trim()
  if (name === '') return { ok: false, reason: 'empty' }
  if (authorNameLength(name) > NEW_AUTHOR_NAME_MAX_LEN) return { ok: false, reason: 'too-long' }
  const key = authorIdentityKey(name)
  if (state.attached.some((a) => authorIdentityKey(a) === key)) {
    return { ok: false, reason: 'duplicate-attached' }
  }
  if (state.pending.some((p) => authorIdentityKey(p) === key)) {
    return { ok: false, reason: 'duplicate-pending' }
  }
  if (state.pending.length >= NEW_AUTHOR_NAMES_MAX_ITEMS) return { ok: false, reason: 'too-many' }
  return { ok: true, names: [...state.pending, name] }
}

/** 移除一个待新建名（按规范身份键匹配；不存在则原样返回） */
export function removePendingAuthorName(pending: readonly string[], name: string): string[] {
  const key = authorIdentityKey(name)
  return pending.filter((p) => authorIdentityKey(p) !== key)
}

/** 加入被拒的提示文案（页面 toast 直接消费，口径单一来源） */
export function pendingAuthorRejectionText(reason: PendingAuthorRejection, name: string): string {
  const n = name.trim()
  switch (reason) {
    case 'empty':
      return '请输入作者名'
    case 'too-long':
      return `作者名超 ${NEW_AUTHOR_NAME_MAX_LEN} 字符，未加入待新建`
    case 'too-many':
      return `待新建作者最多 ${NEW_AUTHOR_NAMES_MAX_ITEMS} 个，请先保存本页再继续添加`
    case 'duplicate-attached':
      return `「${n}」已在关联作者列表中`
    case 'duplicate-pending':
      return `「${n}」已在待新建列表中`
  }
}

/**
 * 保存请求体（PUT /assets/{assetId}/authors）：authorIds=已关联 ID 全集（空=解除
 * 全部常规关联）；newAuthorNames=待新建名（空数组=无新建，与协议缺省等价，显式
 * 带上让"保存带新建名"这一约定在调用点可见、可单测）。
 */
export function authorsReplaceBody(
  authorIds: readonly string[],
  newAuthorNames: readonly string[],
): AssetAuthorsReplaceRequest {
  return { authorIds: [...authorIds], newAuthorNames: [...newAuthorNames] }
}
