import { useRef, useState } from 'react'
import type { AuthorSuggest } from '@/api/generated'
import { useAuthorSuggest } from '@/hooks/use-authors'
import { SUGGEST_DEBOUNCE_MS, useDebouncedValue } from '@/hooks/use-debounced-value'
import { authorIdentityKey, unmatchedCreateName } from '@/lib/asset-authors'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'
import { ClearIcon } from '@/components/shell/icons'

/**
 * 作者联想字段（资产编辑页「添加作者」与上传工作台「批次作者」共用）。
 * 受控 {value, onChange}：
 * - 点选联想项 = 确定作者身份（onChange 带 authorId——选的是身份不是文本）；
 * - allowCreate 开启时（编辑页；协议 PUT /assets/{assetId}/authors 支持
 *   newAuthorNames，2026-10-11 修订）：输入未命中任何建议时面板给出
 *   「新建：<name>」入口（可点选），回车未命中同样产出 {authorName} 形态的
 *   「待新建」结果——与"选中已有作者"（带 authorId）区分，消费方收进待新建
 *   清单随保存提交；
 * - 回车与联想项 displayName 构成同一规范身份（大小写/空白不敏感）时按选定该
 *   身份处理（authorId）；
 * - allowCreate 关闭时（上传工作台：挂靠只认已有作者 ID）面板不提供新建入口，
 *   回车未命中仍产出 {authorName} 形态由消费方按"仅关联"语义处置（既有行为）；
 * - null = 清空动作。
 * 联想 = GET /authors/suggest（hooks 封装，铁律 7）：子串大小写不敏感命中、
 * 别名命中同一作者都由服务端负责，本组件只展示；防抖/Popover 范式照 TopBar
 * 搜索补全（含 .search-pop--popper 面板与 .pop-suggest-* 行样式复用）。
 * 选中后输入框回显 displayName，再编辑即退回自由输入态（onChange(null)）。
 */

/** 联想值：authorId=选中已有作者；authorName=待新建意图（消费方收进待新建清单） */
export interface AuthorAttachValue {
  authorId?: string
  authorName?: string
  displayName?: string
}

/** 协议 id/displayName 均可选；缺任一都无法确定身份——不进可点列表 */
type NamedSuggest = AuthorSuggest & { id: string; displayName: string }

export function AuthorSuggestField({
  value,
  onChange,
  label = '添加作者（联想选择）',
  attachedHint = '已选作者——保存后关联到本作品',
  allowCreate = false,
  disabled = false,
}: {
  value: AuthorAttachValue | null
  onChange: (next: AuthorAttachValue | null) => void
  /** 字段标题（编辑页/上传工作台语境不同；缺省 = 编辑页原文案） */
  label?: string
  /** 已选定作者时的提示行（缺省 = 编辑页语义） */
  attachedHint?: string
  /** 提供「新建：<name>」入口（编辑页开；上传工作台挂靠只认已有作者 ID，缺省关） */
  allowCreate?: boolean
  /** 保存等不可变更阶段禁用输入、清空与联想选项 */
  disabled?: boolean
}) {
  const [text, setText] = useState('')
  const [popOpen, setPopOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)

  // 已确定挂靠（点选已选作者/回车待新建/点选新建）后不再弹联想；编辑即退回自由输入态
  const attached = value != null && (value.authorId != null || value.authorName != null)
  const display = attached ? (value?.displayName ?? value?.authorName ?? '') : text

  const debounced = useDebouncedValue(display, SUGGEST_DEBOUNCE_MS)
  const { data: suggestions = [], isPending } = useAuthorSuggest(debounced, !disabled && popOpen && !attached)
  const rows = suggestions.filter((s): s is NamedSuggest => s.id != null && s.displayName != null)

  const open = !disabled && popOpen && !attached && display.trim() !== ''
  // 未命中任何联想项时的「新建」名（null=空输入或已命中，不给新建入口）
  const createName = allowCreate ? unmatchedCreateName(rows.map((s) => s.displayName), display) : null

  const onInputChange = (raw: string): void => {
    if (disabled) return
    setText(raw)
    if (attached) onChange(null) // 再编辑 = 放弃已确定身份，退回自由输入态
  }

  const onEnter = (): void => {
    if (disabled) return
    const name = display.trim()
    if (name === '') return
    // 回车语义与 Android 端一致（REQ §3.1「交互要求对两端一致」）：
    // 输入与某联想项 displayName 构成同一规范身份 → 视为选定该作者身份
    // （走 authorId）；否则产出待新建形态（新建/复用归服务端 generateAuthorId）。
    const key = authorIdentityKey(name)
    const exact = rows.find((s) => authorIdentityKey(s.displayName) === key)
    if (exact) {
      onChange({ authorId: exact.id, displayName: exact.displayName })
    } else {
      onChange({ authorName: name })
    }
    setPopOpen(false)
    inputRef.current?.blur()
  }

  const clear = (): void => {
    if (disabled) return
    onChange(null)
    setText('')
    inputRef.current?.focus()
  }

  return (
    <label className="settings-field upload-attach-field">
      <span>{label}</span>
      <Popover open={open} onOpenChange={(next) => { if (!disabled) setPopOpen(next) }}>
        <div className="attach-input-box" ref={boxRef}>
          <PopoverAnchor asChild>
            <input
              ref={inputRef}
              type="text"
              placeholder="输入作者名联想选择"
              autoComplete="off"
              value={display}
              disabled={disabled}
              onChange={(e) => onInputChange(e.target.value)}
              onFocus={() => { if (!disabled) setPopOpen(true) }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') onEnter()
              }}
            />
          </PopoverAnchor>
          {display !== '' && (
            <button className="attach-clear" type="button" aria-label="清除已选作者" disabled={disabled} onClick={clear}>
              <ClearIcon />
            </button>
          )}
          {open ? (
            <PopoverContent
              className="search-pop search-pop--popper"
              side="bottom"
              align="start"
              sideOffset={8}
              avoidCollisions={false}
              // 打开不抢焦点：焦点留在输入框继续打字（同 TopBar 口径）
              onOpenAutoFocus={(e) => e.preventDefault()}
              // 输入框区域内点击（定位光标/清空按钮）不关面板
              onInteractOutside={(e) => {
                if (boxRef.current?.contains(e.target as Node)) e.preventDefault()
              }}
            >
              {isPending ? (
                <p className="pop-suggest-hint">正在获取联想…</p>
              ) : rows.length === 0 && createName == null ? (
                <p className="pop-suggest-hint">没有匹配的作者——新建作者请先经 TXT 导入创建</p>
              ) : (
                <div className="pop-suggest-list">
                  {rows.map((s) => (
                    <button
                      key={s.id}
                      className="pop-suggest-item"
                      type="button"
                      disabled={disabled}
                      onClick={() => {
                        onChange({ authorId: s.id, displayName: s.displayName })
                        setPopOpen(false)
                      }}
                    >
                      <span className="pop-suggest-name">{s.displayName}</span>
                      <span className="pop-suggest-badge">{s.fileCount ?? 0} 个文件</span>
                    </button>
                  ))}
                  {createName != null && (
                    <button
                      className="pop-suggest-item"
                      type="button"
                      disabled={disabled}
                      onClick={() => {
                        onChange({ authorName: createName })
                        setPopOpen(false)
                      }}
                    >
                      <span className="pop-suggest-name">新建：{createName}</span>
                      <span className="pop-suggest-badge">新作者</span>
                    </button>
                  )}
                </div>
              )}
            </PopoverContent>
          ) : null}
        </div>
      </Popover>
      <small>
        {attached
          ? value?.authorId != null
            ? attachedHint
            : allowCreate
              ? '待新建作者——保存后自动创建并关联（批量导入多作者仍走 TXT 导入）'
              : '新建作者请先经 TXT 导入创建'
          : allowCreate
            ? '输入名字从联想中点选；没有的姓名可点「新建」或回车新建'
            : '输入名字从联想中点选要关联的作者'}
      </small>
    </label>
  )
}
