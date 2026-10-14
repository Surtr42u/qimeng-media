import { AlertDialog } from 'radix-ui'
import { cn } from '@/lib/utils'

/**
 * 确认弹窗（W-2：替代 window.confirm 的原型风格二次确认）。
 * radix AlertDialog 封装——web 现有依赖是 `radix-ui` 统一包（button.tsx 同源），
 * AlertDialog 为其中一个命名空间，无需新增 @radix-ui/react-alert-dialog 依赖。
 * 样式全部走原型 token（prototype.css 追加段 .confirm-*），组件层零颜色字面量。
 * ESC 关闭 / 焦点圈禁 / 初始聚焦由 radix 自带；danger = 物理删除/清空类主按钮
 * 深色强调（原型色板无红，用户拍板语义；原型规范历史见 CHANGELOG）。
 */
export interface ConfirmDialogProps {
  open: boolean
  title: string
  description: string
  confirmText: string
  cancelText: string
  /** 物理删除/清空类操作：主按钮深色强调（非红色） */
  danger?: boolean
  /** 异步操作进行中：禁用重复提交与取消，避免 UI 提前关闭 */
  busy?: boolean
  /** 异步确认需保留弹窗直到调用方明确关闭；默认同步操作仍在确认后关闭 */
  keepOpenOnConfirm?: boolean
  onConfirm: () => void
  /** 关闭请求通道（ESC / 取消按钮）：受控 open 必须由父组件响应它才能真正关闭 */
  onOpenChange?: (open: boolean) => void
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmText,
  cancelText,
  danger = false,
  busy = false,
  keepOpenOnConfirm = false,
  onConfirm,
  onOpenChange,
}: ConfirmDialogProps) {
  return (
    <AlertDialog.Root open={open} onOpenChange={onOpenChange}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="confirm-overlay" />
        <AlertDialog.Content className="confirm-panel">
          <AlertDialog.Title className="confirm-title">{title}</AlertDialog.Title>
          <AlertDialog.Description className="confirm-desc">{description}</AlertDialog.Description>
          <div className="confirm-actions">
            <AlertDialog.Cancel className="confirm-btn confirm-btn--cancel" disabled={busy}>{cancelText}</AlertDialog.Cancel>
            <button
              type="button"
              className={cn('confirm-btn', danger ? 'confirm-btn--danger' : 'confirm-btn--primary')}
              disabled={busy}
              onClick={() => {
                onConfirm()
                if (!keepOpenOnConfirm) onOpenChange?.(false)
              }}
            >
              {confirmText}
            </button>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  )
}
