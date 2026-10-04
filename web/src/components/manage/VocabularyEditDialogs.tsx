import { useState } from 'react'
import { Dialog } from 'radix-ui'
import { runeLength, truncateToRuneMax, VOCABULARY_LIMITS } from '@/lib/vocabulary-edit'

/**
 * 词表维护弹窗（App ADR-0035「词表维护」web 移植，2026-10-04；对位 App 端
 * VocabularyEditDialogs.kt 的 VocabularyPromptDialog）。单输入框机制：新增出处组/
 * 组改名/新增变体/新增角色/角色改名/新增别名/新增停用词共用，差异只在标题、
 * 初值与确认回调（页面层统一持有 pendingPrompt）。确认传**未 trim 原文**——
 * 去空/去重是服务端 PUT 规范化职责，UI 只拦空白提交；与保存后回读的规范化
 * 差异由保存成功后的失效重拉抹平。放弃确认不在此文件——复用共享件
 * ConfirmDialog（radix AlertDialog），文案见词表维护页。
 */

/** 单输入框对话框请求（页面层构建；onConfirm 收未 trim 原文） */
export interface VocabularyPromptRequest {
  title: string
  initial?: string
  onConfirm: (text: string) => void
}

/** 按条件挂载（父组件 `{prompt && …}`）：每次打开即复位输入，规避 set-state-in-effect（CreateDirDialog 同款） */
export function VocabularyPromptDialog({
  request,
  onDismiss,
}: {
  request: VocabularyPromptRequest
  onDismiss: () => void
}) {
  const [text, setText] = useState(request.initial ?? '')
  const canConfirm = text.trim() !== ''

  const submit = (): void => {
    if (!canConfirm) return
    onDismiss()
    request.onConfirm(text)
  }

  return (
    <Dialog.Root open onOpenChange={(next) => !next && onDismiss()}>
      <Dialog.Portal>
        <Dialog.Overlay className="detail-dialog-overlay" />
        <Dialog.Content className="detail-dialog detail-dialog--vocab-prompt">
          <Dialog.Title className="detail-dialog-title">{request.title}</Dialog.Title>
          <Dialog.Description className="detail-dialog-desc">
            输入词条（自动去除首尾空格，服务端保存时去重）
          </Dialog.Description>
          <div className="tag-newrow" style={{ marginTop: 14 }}>
            <input
              className="tag-input"
              type="text"
              value={text}
              placeholder="输入词条，回车确认"
              autoFocus
              onChange={(e) => setText(truncateToRuneMax(e.target.value, VOCABULARY_LIMITS.TEXT_MAX))}
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
            {runeLength(text)}/{VOCABULARY_LIMITS.TEXT_MAX}
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
