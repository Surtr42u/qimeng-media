import { useState } from 'react'
import { Dialog } from 'radix-ui'
import { toast } from 'sonner'
import { DirTreeNodes } from '@/components/manage/DirTree'
import { useDirTree } from '@/hooks/use-libraries'
import { useMoveAsset } from '@/hooks/use-file-ops'
import { dirLabel } from '@/lib/format'

/**
 * 移动/重命名弹窗（B-1 抽共享组件）：一个端点两用——POST /assets/{id}/move
 * 的 targetDir 必填 + newName 可选。改名 = 原目录 + 新名；移动 = 新目录 +
 * 原名；同弹窗内可一次完成两者。目标目录选择复用共享 DirTreeNodes 选择
 * 模式（与上传卡同一交互语言，树数据源 useDirTree 绑定资产所属库）。
 * 调用方：详情页 FileOpsButton；目录树文件行（FileOpsDialogs）；
 * F6 批量移动（batch 模式：复用本弹窗的目录树选目标，隐藏改名输入，
 * 确认后把 targetDir 交回调用方跑批量执行器——逐条 POST 不在本弹窗）。
 * 弹窗按 open 条件挂载 + useState 惰性初始化（TagDialog 模式，打开即复位）。
 */
export interface MoveDialogProps {
  open: boolean
  /** 单文件模式的资产 id；batch 模式不传（逐条 move 由调用方循环发起） */
  assetId?: string
  /** 资产所属库（目录树必须锚定同一库——move 的 targetDir 是库内相对路径） */
  libraryId: string
  /** 当前所在目录（库内相对路径，'' = 库根；初始选中态） */
  currentDir: string
  /** 当前文件名（输入框预填；重命名 = 改这个值） */
  currentName: string
  /** 批量移动模式：count = 参与条数，onConfirm = 目标目录确认回调 */
  batch?: { count: number; onConfirm: (targetDir: string) => void }
  onClose: () => void
}

export function MoveDialog({
  open, assetId, libraryId, currentDir, currentName, batch, onClose,
}: MoveDialogProps) {
  // 惰性初始化：弹窗每次打开都从当前现实复位（组件按 open 条件挂载）
  const [targetDir, setTargetDir] = useState(currentDir)
  const [name, setName] = useState(currentName)
  const { data: tree, isFetching } = useDirTree(libraryId, open)
  const moveAsset = useMoveAsset()

  // 批量模式：只选目标目录（逐条 POST move / 失败汇总由调用方的批量执行器负责）
  const isBatch = batch !== undefined

  const trimmed = name.trim()
  const renamed = trimmed !== '' && trimmed !== currentName
  const moved = targetDir !== currentDir
  // 无任何变化时禁用提交（服务端对同位置调用回 200 幂等，但按钮该给真实反馈）
  const submittable = isBatch ? moved : trimmed !== '' && (renamed || moved)

  const submit = (): void => {
    if (!submittable) return
    if (isBatch) {
      batch.onConfirm(targetDir)
      onClose()
      return
    }
    moveAsset.mutate(
      { assetId: assetId ?? '', targetDir, newName: renamed ? trimmed : undefined },
      {
        onSuccess: () => {
          toast.success(
            renamed && moved
              ? `已移动到「${dirLabel(targetDir)}」并重命名为「${trimmed}」`
              : renamed
                ? `已重命名为「${trimmed}」`
                : `已移动到「${dirLabel(targetDir)}」`,
          )
          onClose()
        },
        onError: (err) =>
          // 服务端文案透传（如 409「目标位置已有同名文件」）
          toast.error(`整理失败：${err instanceof Error ? err.message : String(err)}`),
      },
    )
  }

  if (!open) return null

  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="detail-dialog-overlay" />
        <Dialog.Content className="detail-dialog move-dialog">
          <Dialog.Title className="detail-dialog-title">
            {isBatch ? `批量移动 ${batch.count} 个文件` : '整理文件'}
          </Dialog.Title>
          <Dialog.Description className="detail-dialog-desc">
            {isBatch
              ? '选择目标目录后逐个移动；与目标位置已有文件同名时该文件将被拒绝（不覆盖），不影响其余文件。'
              : '选择目标目录或修改文件名；目标位置已有同名文件时将被拒绝（不覆盖）。'}
          </Dialog.Description>
          <div className="move-dir-box">
            {isFetching || !tree ? (
              <p className="grid-empty">目录加载中…</p>
            ) : (
              <ul>
                <DirTreeNodes
                  node={tree}
                  depth={0}
                  selectedPath={targetDir}
                  onSelect={(node) => setTargetDir(node.path ?? '')}
                />
              </ul>
            )}
          </div>
          {!isBatch && (
            <label className="move-name-row">
              <span>文件名</span>
              <input
                className="tag-input"
                value={name}
                placeholder="新文件名（含扩展名）"
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') submit()
                }}
              />
            </label>
          )}
          <div className="detail-dialog-foot">
            <button className="confirm-btn confirm-btn--cancel" onClick={onClose}>
              取消
            </button>
            <button
              className="confirm-btn confirm-btn--primary"
              onClick={submit}
              disabled={!submittable || moveAsset.isPending}
            >
              {isBatch ? `移动到「${dirLabel(targetDir)}」` : moveAsset.isPending ? '保存中…' : '保存'}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
