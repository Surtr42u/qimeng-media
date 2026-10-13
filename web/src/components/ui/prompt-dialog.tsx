import { useState } from 'react'
import { Dialog } from 'radix-ui'
import { runeLength, truncateToRuneMax } from '@/lib/vocabulary-edit'
import { cn } from '@/lib/utils'

/**
 * 单输入框对话框（共享件，2026-10-13 抽出：词表维护页 VocabularyPromptDialog 与
 * 作者管理页新建/改名对话框的共同形态——第 2 次出现即抽，禁止第 3 次粘贴）。
 * radix Dialog 行为层（点外/ESC 关闭、焦点圈禁自带），样式指回 prototype.css
 * 既有类（detail-dialog 族 + tag-input；计数用 .vocab-counter，组件层零样式字面量）。
 *
 * 按条件挂载（父组件 `{state && <PromptDialog … />}`）：每次打开即复位输入，
 * 规避 set-state-in-effect（CreateDirDialog 同款）。确认传**未 trim 原文**——
 * 去空/规范化是各页服务端职责，UI 只拦空白提交（确认钮禁用）。
 */
export interface PromptDialogProps {
  title: string
  /** 副标题说明（口径提示，各页自定文案） */
  description: string
  placeholder: string
  initial?: string
  /** rune（码点）上限：输入超限截断；右下角回显 计数/上限 */
  maxRunes: number
  /** 内容容器附加类（各页容器微调，如词表页窄容器） */
  contentClassName?: string
  onConfirm: (text: string) => void
  onDismiss: () => void
}

export function PromptDialog({
  title,
  description,
  placeholder,
  initial,
  maxRunes,
  contentClassName,
  onConfirm,
  onDismiss,
}: PromptDialogProps) {
  const [text, setText] = useState(initial ?? '')
  const canConfirm = text.trim() !== ''

  const submit = (): void => {
    if (!canConfirm) return
    onDismiss()
    onConfirm(text)
  }

  return (
    <Dialog.Root open onOpenChange={(next) => !next && onDismiss()}>
      <Dialog.Portal>
        <Dialog.Overlay className="detail-dialog-overlay" />
        <Dialog.Content className={cn('detail-dialog', contentClassName)}>
          <Dialog.Title className="detail-dialog-title">{title}</Dialog.Title>
          <Dialog.Description className="detail-dialog-desc">{description}</Dialog.Description>
          <div className="tag-newrow" style={{ marginTop: 14 }}>
            <input
              className="tag-input"
              type="text"
              value={text}
              placeholder={placeholder}
              autoFocus
              onChange={(e) => setText(truncateToRuneMax(e.target.value, maxRunes))}
              onKeyDown={(e) => {
                if (e.key === 'Enter') submit()
              }}
            />
            <button
              type="button"
              className="confirm-btn confirm-btn--primary"
              onClick={submit}
              disabled={!canConfirm}
              title="确认"
            >
              确定
            </button>
          </div>
          <div className="vocab-counter" style={{ marginTop: 6 }}>
            {runeLength(text)}/{maxRunes}
          </div>
          <div className="detail-dialog-foot">
            <button type="button" className="confirm-btn confirm-btn--cancel" onClick={onDismiss}>
              取消
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
