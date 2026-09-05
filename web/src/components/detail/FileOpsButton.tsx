import { useState } from 'react'
import { useNavigate } from 'react-router'
import { FolderInput, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import type { AssetDetail } from '@/api/generated'
import { MoveDialog } from '@/components/manage/MoveDialog'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { useDeleteAsset } from '@/hooks/use-file-ops'

/**
 * 详情页文件整理入口（B-1 文件管理操作化）——互动行「整理/删除」按钮 + 弹窗编排。
 * 背景：目录树协议（GET /dirs）只回目录与计数、GET /assets 无目录过滤，
 * 文件行操作在文件管理页无处挂载；详情页有 assetId/libraryId/directory 完整
 * 上下文，是文件三操作（重命名/移动/移入回收站）的可行挂载点（待拍板：
 * 目录树文件行需协议扩展，见 HANDOVER_UI §5）。
 * - 重命名/移动：MoveDialog（POST /move 一个端点两用）；
 * - 删除：ConfirmDialog danger 二次确认，文案明示「移入回收站」语义
 *   （铁律 4：DELETE 永不物理删，恢复走维护页回收站）。删除成功后
 *   navigate(-1) 离开详情（资产已不存在）；toast 挂在根 Toaster 上，
 *   组件卸载后仍可见。
 */
export function FileOpsButton({ asset }: { asset: AssetDetail }) {
  const [mode, setMode] = useState<'none' | 'move' | 'delete'>('none')
  const navigate = useNavigate()
  const deleteAsset = useDeleteAsset()

  const confirmDelete = (): void => {
    const assetId = asset.id
    const fileName = asset.fileName ?? ''
    setMode('none')
    if (!assetId) return
    deleteAsset.mutate(assetId, {
      onSuccess: () => {
        toast.success(`「${fileName}」已移入回收站（可在维护页恢复）`)
        navigate(-1)
      },
      onError: (err) =>
        toast.error(`移入回收站失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  return (
    <>
      <button
        className="detail-act"
        onClick={() => setMode('move')}
        title="移动到其他目录 / 重命名"
      >
        <FolderInput />
        <b>整理</b>
      </button>
      <button
        className="detail-act"
        onClick={() => setMode('delete')}
        title="移入回收站（可在维护页恢复）"
      >
        <Trash2 />
        <b>删除</b>
      </button>

      {mode === 'move' && asset.id && asset.libraryId ? (
        <MoveDialog
          open
          assetId={asset.id}
          libraryId={asset.libraryId}
          currentDir={asset.directory ?? ''}
          currentName={asset.fileName ?? ''}
          onClose={() => setMode('none')}
        />
      ) : null}
      <ConfirmDialog
        open={mode === 'delete'}
        title={`移入回收站「${asset.fileName ?? ''}」？`}
        description={'文件将移入回收站并从媒体库移除（浏览/点赞等记录保留）。\n可在维护页「回收站」恢复。'}
        confirmText="移入回收站"
        cancelText="取消"
        danger
        onConfirm={confirmDelete}
        onOpenChange={(next) => {
          if (!next) setMode('none')
        }}
      />
    </>
  )
}
