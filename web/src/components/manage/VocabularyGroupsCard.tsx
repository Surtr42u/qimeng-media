import { Fragment, useState } from 'react'
import type { CustomSourceGroup } from '@/api/generated'
import { VOCABULARY_LIMITS } from '@/lib/vocabulary-edit'

/**
 * 自定义出处组卡（词表维护页主体，App ADR-0035「词表维护」web 移植，对位 App 端
 * VocabularyEditGroupsCard.kt）：组行（规范名 + 词条计数，点按展开/收起，展开态互斥）
 * → 变体写法小节 + 角色小节（行内改名/移除，点按展开别名小节，展开态互斥）。
 * 全部操作经回调转页面层编辑状态机（铁律 7：本组件零业务规则，只做列表渲染
 * 与转发）；新增一律走 VocabularyPromptDialog（页面层统一持有 pendingPrompt）。
 * 展开态是纯 UI 状态（不进编辑副本/不置脏）。
 */

/** 卡与小节文案（与 App 端 VocabularyEditGroupsCard.kt 常量逐字对齐；「内置 133 组」为 2026-10-04 固化后权威口径） */
const CARD_TITLE = '自定义出处组'
const GROUPS_EMPTY = '没有自定义出处组（内置 133 组基线始终生效，这里只维护追加词层）'
const GROUP_ADD = '添加组'
const GROUP_RENAME = '改名'
const GROUP_REMOVE = '移除'
const ADD_ITEM = '添加'
const SECTION_VARIANTS = '变体写法'
const SECTION_CHARACTERS = '角色检索表'
const SECTION_ALIASES = '别名'
const SUBTITLE_NONE = '无词条'
const EXPANDED_HINT = '已展开，点组名行收起'
const VARIANTS_EMPTY = '暂无变体（规范名自身恒参与前缀匹配与剥离）'
const CHARACTERS_EMPTY = '暂无角色（兜底提取可按命名规约自动出角色）'
const ALIASES_EMPTY = '暂无别名（规范名自身恒参与子串匹配）'
const CHARACTER_EXPANDED_SUFFIX = '（含别名）'

/** 未展开哨兵（App 端 -1 同义） */
const COLLAPSED = -1

export interface VocabularyGroupsCardProps {
  groups: CustomSourceGroup[]
  busy: boolean
  onAddGroup: () => void
  onRemoveGroup: (index: number) => void
  onRenameGroup: (index: number, current: string) => void
  onAddVariant: (index: number) => void
  onRemoveVariant: (groupIndex: number, variantIndex: number) => void
  onAddCharacter: (index: number) => void
  onRemoveCharacter: (groupIndex: number, characterIndex: number) => void
  onRenameCharacter: (groupIndex: number, characterIndex: number, current: string) => void
  onAddAlias: (groupIndex: number, characterIndex: number) => void
  onRemoveAlias: (groupIndex: number, characterIndex: number, aliasIndex: number) => void
}

export function VocabularyGroupsCard({
  groups,
  busy,
  onAddGroup,
  onRemoveGroup,
  onRenameGroup,
  onAddVariant,
  onRemoveVariant,
  onAddCharacter,
  onRemoveCharacter,
  onRenameCharacter,
  onAddAlias,
  onRemoveAlias,
}: VocabularyGroupsCardProps) {
  // 展开态互斥：单组展开 + 组内单角色展开（App 端 rememberSaveable 的 useState 对位）
  const [expandedGroup, setExpandedGroup] = useState(COLLAPSED)
  const [expandedCharacter, setExpandedCharacter] = useState(COLLAPSED)

  const toggleGroup = (index: number): void => {
    setExpandedCharacter(COLLAPSED)
    setExpandedGroup((cur) => (cur === index ? COLLAPSED : index))
  }

  return (
    <div className="settings-card">
      <div className="vocab-head">
        <h3>{CARD_TITLE}</h3>
        <button
          type="button"
          className="vocab-text-btn"
          disabled={busy || groups.length >= VOCABULARY_LIMITS.GROUPS_MAX}
          onClick={onAddGroup}
        >
          {GROUP_ADD}
        </button>
      </div>
      {groups.length === 0 ? (
        <p className="vocab-note">{GROUPS_EMPTY}</p>
      ) : (
        groups.map((group, index) => (
          <div key={index} className={index > 0 ? 'vocab-divider' : undefined}>
            <div
              className="vocab-row vocab-row--clickable"
              role="button"
              tabIndex={0}
              onClick={() => toggleGroup(index)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  toggleGroup(index)
                }
              }}
            >
              <span className="vocab-row-main">
                <span className="vocab-name">{group.canonical}</span>
                <span className="vocab-sub">{groupSubtitle(group)}</span>
              </span>
              <button
                type="button"
                className="vocab-text-btn"
                disabled={busy}
                onClick={(e) => {
                  e.stopPropagation()
                  onRenameGroup(index, group.canonical)
                }}
              >
                {GROUP_RENAME}
              </button>
              <button
                type="button"
                className="vocab-text-btn"
                disabled={busy}
                onClick={(e) => {
                  e.stopPropagation()
                  onRemoveGroup(index)
                }}
              >
                {GROUP_REMOVE}
              </button>
            </div>
            {expandedGroup === index ? (
              <div className="vocab-indent">
                <p className="vocab-note vocab-note--indent">{EXPANDED_HINT}</p>

                <SectionHead
                  title={SECTION_VARIANTS}
                  addDisabled={busy || (group.variants?.length ?? 0) >= VOCABULARY_LIMITS.VARIANTS_MAX}
                  onAdd={() => onAddVariant(index)}
                />
                {(group.variants ?? []).length === 0 ? (
                  <p className="vocab-note">{VARIANTS_EMPTY}</p>
                ) : (
                  (group.variants ?? []).map((variant, variantIndex) => (
                    <EntryRow
                      key={variantIndex}
                      text={variant}
                      busy={busy}
                      onRemove={() => onRemoveVariant(index, variantIndex)}
                    />
                  ))
                )}

                <SectionHead
                  title={SECTION_CHARACTERS}
                  addDisabled={busy || (group.characters?.length ?? 0) >= VOCABULARY_LIMITS.CHARACTERS_MAX}
                  onAdd={() => onAddCharacter(index)}
                />
                {(group.characters ?? []).length === 0 ? (
                  <p className="vocab-note">{CHARACTERS_EMPTY}</p>
                ) : (
                  (group.characters ?? []).map((character, characterIndex) => (
                    <Fragment key={characterIndex}>
                      <div
                        className="vocab-row vocab-row--clickable"
                        role="button"
                        tabIndex={0}
                        onClick={() =>
                          setExpandedCharacter((cur) => (cur === characterIndex ? COLLAPSED : characterIndex))
                        }
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            setExpandedCharacter((cur) =>
                              cur === characterIndex ? COLLAPSED : characterIndex,
                            )
                          }
                        }}
                      >
                        <span className="vocab-item-text">
                          {character.canonical}
                          {expandedCharacter === characterIndex ? CHARACTER_EXPANDED_SUFFIX : ''}
                        </span>
                        <button
                          type="button"
                          className="vocab-text-btn"
                          disabled={busy}
                          onClick={(e) => {
                            e.stopPropagation()
                            onRenameCharacter(index, characterIndex, character.canonical)
                          }}
                        >
                          {GROUP_RENAME}
                        </button>
                        <button
                          type="button"
                          className="vocab-text-btn"
                          disabled={busy}
                          onClick={(e) => {
                            e.stopPropagation()
                            onRemoveCharacter(index, characterIndex)
                          }}
                        >
                          {GROUP_REMOVE}
                        </button>
                      </div>
                      {expandedCharacter === characterIndex ? (
                        <div className="vocab-indent">
                          <SectionHead
                            title={SECTION_ALIASES}
                            addDisabled={
                              busy || (character.aliases?.length ?? 0) >= VOCABULARY_LIMITS.ALIASES_MAX
                            }
                            onAdd={() => onAddAlias(index, characterIndex)}
                          />
                          {(character.aliases ?? []).length === 0 ? (
                            <p className="vocab-note">{ALIASES_EMPTY}</p>
                          ) : (
                            (character.aliases ?? []).map((alias, aliasIndex) => (
                              <EntryRow
                                key={aliasIndex}
                                text={alias}
                                busy={busy}
                                onRemove={() => onRemoveAlias(index, characterIndex, aliasIndex)}
                              />
                            ))
                          )}
                        </div>
                      ) : null}
                    </Fragment>
                  ))
                )}
              </div>
            ) : null}
          </div>
        ))
      )}
    </div>
  )
}

/** 组词条计数副行（变体/角色数；空组给「无词条」占位——App groupSubtitle 对位） */
function groupSubtitle(group: CustomSourceGroup): string {
  const variantCount = group.variants?.length ?? 0
  const characterCount = group.characters?.length ?? 0
  if (variantCount === 0 && characterCount === 0) return SUBTITLE_NONE
  return `${SECTION_VARIANTS} ${variantCount} · ${SECTION_CHARACTERS} ${characterCount}`
}

/** 小节头：小节名 + 添加钮（容量封顶时禁用） */
function SectionHead({
  title,
  addDisabled,
  onAdd,
}: {
  title: string
  addDisabled: boolean
  onAdd: () => void
}) {
  return (
    <div className="vocab-section-head">
      <span className="vocab-section-title">{title}</span>
      <button type="button" className="vocab-text-btn" disabled={addDisabled} onClick={onAdd}>
        {ADD_ITEM}
      </button>
    </div>
  )
}

/** 词条行：文本 + 移除（变体/别名共用） */
function EntryRow({ text, busy, onRemove }: { text: string; busy: boolean; onRemove: () => void }) {
  return (
    <div className="vocab-row">
      <span className="vocab-item-text">{text}</span>
      <button type="button" className="vocab-text-btn" disabled={busy} onClick={onRemove}>
        {GROUP_REMOVE}
      </button>
    </div>
  )
}
