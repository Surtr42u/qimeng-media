import { VOCABULARY_LIMITS } from '@/lib/vocabulary-edit'
import { PromptDialog } from '@/components/ui/prompt-dialog'

/**
 * 词表维护弹窗（App ADR-0035「词表维护」web 移植，2026-10-04；对位 App 端
 * VocabularyEditDialogs.kt 的 VocabularyPromptDialog）。单输入框机制：新增出处组/
 * 组改名/新增变体/新增角色/角色改名/新增别名/新增停用词共用，差异只在标题、
 * 初值与确认回调（页面层统一持有 pendingPrompt）。确认传**未 trim 原文**——
 * 去空/去重是服务端 PUT 规范化职责，UI 只拦空白提交；与保存后回读的规范化
 * 差异由保存成功后的失效重拉抹平。放弃确认不在此文件——复用共享件
 * ConfirmDialog（radix AlertDialog），文案见词表维护页。
 *
 * 2026-10-13：对话框壳体抽到共享件 components/ui/prompt-dialog（作者管理页
 * 新建/改名对话框同形态——第 2 次出现即抽）；本组件保留词表语的文案/限额并
 * 转调共享件，页面层 API（request/onDismiss）不变。
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
  return (
    <PromptDialog
      title={request.title}
      description="输入词条（自动去除首尾空格，服务端保存时去重）"
      placeholder="输入词条，回车确认"
      initial={request.initial}
      maxRunes={VOCABULARY_LIMITS.TEXT_MAX}
      contentClassName="detail-dialog--vocab-prompt"
      onConfirm={request.onConfirm}
      onDismiss={onDismiss}
    />
  )
}
