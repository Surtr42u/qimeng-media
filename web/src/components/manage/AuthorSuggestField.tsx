import { useRef, useState } from 'react'
import { useAuthorSuggest } from '@/hooks/use-authors'
import { SUGGEST_DEBOUNCE_MS, useDebouncedValue } from '@/hooks/use-debounced-value'
import { Popover, PopoverAnchor, PopoverContent } from '@/components/ui/popover'
import { ClearIcon } from '@/components/shell/icons'

/**
 * 作者联想字段（资产编辑页消费，ADR-0024——上传挂靠入口已退役）。
 * 受控 {value, onChange}：
 * - 点选联想项 = 确定作者身份（onChange 带 authorId——选的是身份不是文本）；
 * - 回车 = 输入与联想项 displayName 大小写不敏感全等时选定该作者；无匹配
 *   回车产出 authorName 形态——本组件不做创建（协议无按名新建作者端点，
 *   新建作者先经 TXT 导入），消费方负责对该形态给出提示；
 * - null = 清空动作。
 * 联想 = GET /authors/suggest（hooks 封装，铁律 7）：子串大小写不敏感命中、
 * 别名命中同一作者都由服务端负责，本组件只展示；防抖/Popover 范式照 TopBar
 * 搜索补全（含 .search-pop--popper 面板与 .pop-suggest-* 行样式复用）。
 * 选中后输入框回显 displayName，再编辑即退回自由输入态（onChange(null)）。
 */

/** 联想值：authorName 形态=新建意图（消费方提示）；displayName 仅前端回显用，不进协议 */
export interface AuthorAttachValue {
  authorId?: string
  authorName?: string
  displayName?: string
}

export function AuthorSuggestField({
  value,
  onChange,
}: {
  value: AuthorAttachValue | null
  onChange: (next: AuthorAttachValue | null) => void
}) {
  const [text, setText] = useState('')
  const [popOpen, setPopOpen] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)

  // 已确定挂靠（点选或回车新建）后不再弹联想；编辑即退回自由输入态
  const attached = value != null && (value.authorId != null || value.authorName != null)
  const display = attached ? (value?.displayName ?? value?.authorName ?? '') : text

  const debounced = useDebouncedValue(display, SUGGEST_DEBOUNCE_MS)
  const { data: suggestions = [], isPending } = useAuthorSuggest(debounced, popOpen && !attached)
  // 协议 id/displayName 均可选；缺 id 无法确定身份（正常响应不会缺），不进可点列表
  const rows = suggestions.filter((s) => s.id != null && s.displayName != null)

  const open = popOpen && !attached && display.trim() !== ''

  const onInputChange = (raw: string): void => {
    setText(raw)
    if (attached) onChange(null) // 再编辑 = 放弃已确定身份，退回自由输入态
  }

  const onEnter = (): void => {
    const name = display.trim()
    if (name === '') return
    // 回车语义与 Android 端一致（REQ §3.1「交互要求对两端一致」）：
    // 输入与某联想项 displayName 大小写不敏感全等 → 视为选定该作者身份
    // （走 authorId）；否则按输入原文新建（归一在服务端 generateAuthorId）。
    const exact = rows.find((s) => s.displayName != null && s.displayName.toLowerCase() === name.toLowerCase())
    if (exact && exact.id != null) {
      onChange({ authorId: exact.id, displayName: exact.displayName })
    } else {
      onChange({ authorName: name })
    }
    setPopOpen(false)
    inputRef.current?.blur()
  }

  const clear = (): void => {
    onChange(null)
    setText('')
    inputRef.current?.focus()
  }

  return (
    <label className="settings-field upload-attach-field">
      <span>添加作者（联想选择）</span>
      <Popover open={open} onOpenChange={setPopOpen}>
        <div className="attach-input-box" ref={boxRef}>
          <PopoverAnchor asChild>
            <input
              ref={inputRef}
              type="text"
              placeholder="输入作者名联想选择"
              autoComplete="off"
              value={display}
              onChange={(e) => onInputChange(e.target.value)}
              onFocus={() => setPopOpen(true)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') onEnter()
              }}
            />
          </PopoverAnchor>
          {display !== '' && (
            <button className="attach-clear" type="button" aria-label="清除已选作者" onClick={clear}>
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
              ) : rows.length > 0 ? (
                <div className="pop-suggest-list">
                  {rows.map((s) => (
                    <button
                      key={s.id}
                      className="pop-suggest-item"
                      type="button"
                      onClick={() => {
                        onChange({ authorId: s.id, displayName: s.displayName })
                        setPopOpen(false)
                      }}
                    >
                      <span className="pop-suggest-name">{s.displayName}</span>
                      <span className="pop-suggest-badge">{s.fileCount ?? 0} 个文件</span>
                    </button>
                  ))}
                </div>
              ) : (
                <p className="pop-suggest-hint">没有匹配的作者——新建作者请先经 TXT 导入创建</p>
              )}
            </PopoverContent>
          ) : null}
        </div>
      </Popover>
      <small>
        {attached
          ? value?.authorId != null
            ? '已选作者——保存后关联到本作品'
            : '新建作者请先经 TXT 导入创建'
          : '输入名字从联想中点选要关联的作者'}
      </small>
    </label>
  )
}
