import { toast } from 'sonner'
import { MoveDialog } from '@/components/manage/MoveDialog'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { useDeleteAsset } from '@/hooks/use-file-ops'

/**
 * 文件三操作的弹窗编排（B-5 从详情页 FileOpsButton 抽共享——代码卫生 6：
 * 确认文案/toast/删除流第二处出现即提取，不复制粘贴）：
 * - 重命名/移动：MoveDialog（POST /move 一个端点两用）；
 * - 删除：ConfirmDialog danger 二次确认，文案明示「移入回收站」语义
 *   （铁律 4：DELETE 永不物理删，恢复走维护页回收站）。
 * 本组件只挂弹窗、不渲染触发按钮（按钮形态两处调用方不同：详情页大按钮、
 * 目录树文件行 hover 行内小按钮）；mode 由调用方持有（'none' = 全关）。
 * 挂载形态与抽取前 FileOpsButton 逐语义一致（勿改）：
 * - MoveDialog 条件挂载——它靠 useState 惰性初始化做"每次打开从当前现实
 *   复位"（内部注释明示组件按 open 条件挂载），改常驻会把初始空值冻住，
 *   实测翻车（预填名变空串、保存即移库）；
 * - ConfirmDialog 保持常驻受控（open=false 仅关闭）——B-2 弹层进出场依赖
 *   常驻挂载，条件挂载会丢关闭动画。
 * 删除成功 toast 挂根 Toaster，组件卸载后仍可见。
 */
export interface FileOpsTarget {
  assetId: string
  /** 资产所属库（MoveDialog 目录树必须锚定同一库——targetDir 是库内相对路径） */
  libraryId: string
  /** 当前所在目录（库内相对路径，'' = 库根；MoveDialog 初始选中态） */
  directory: string
  /** 当前文件名（MoveDialog 输入框预填；确认弹窗标题展示） */
  fileName: string
}

export type FileOpsMode = 'none' | 'move' | 'delete'

export interface FileOpsDialogsProps {
  mode: FileOpsMode
  target: FileOpsTarget
  onClose: () => void
  /** 删除成功后的调用方收尾（详情页 navigate(-1) 离开已删资产；目录树
   *  文件行靠 useDeleteAsset 失效自动刷新列表，不传） */
  onDeleted?: () => void
}

export function FileOpsDialogs({ mode, target, onClose, onDeleted }: FileOpsDialogsProps) {
  const deleteAsset = useDeleteAsset()

  const confirmDelete = (): void => {
    const { assetId, fileName } = target
    onClose()
    if (!assetId) return
    deleteAsset.mutate(assetId, {
      onSuccess: () => {
        toast.success(`「${fileName}」已移入回收站（可在维护页恢复）`)
        onDeleted?.()
      },
      onError: (err) =>
        toast.error(`移入回收站失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  return (
    <>
      {mode === 'move' && target.assetId !== '' && target.libraryId !== '' ? (
        <MoveDialog
          open
          assetId={target.assetId}
          libraryId={target.libraryId}
          currentDir={target.directory}
          currentName={target.fileName}
          onClose={onClose}
        />
      ) : null}
      <ConfirmDialog
        open={mode === 'delete'}
        title={`移入回收站「${target.fileName}」？`}
        description={'文件将移入回收站并从媒体库移除（浏览/点赞等记录保留）。\n可在维护页「回收站」恢复。'}
        confirmText="移入回收站"
        cancelText="取消"
        danger
        onConfirm={confirmDelete}
        onOpenChange={(next) => {
          if (!next) onClose()
        }}
      />
    </>
  )
}
