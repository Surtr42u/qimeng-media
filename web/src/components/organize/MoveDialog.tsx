/**
 * 移动/重命名对话框（详情页 / 列表页复用；移动逻辑属服务端，客户端只选目标）。
 *
 * 【接口文档（给主 AI 接线详情页用）】
 * - 用法：<MoveDialog assetId={id} defaultDir={detail.directory} open={open} onOpenChange={setOpen} settled={Boolean(detail)} />
 * - defaultDir：资产当前目录（库内相对路径，空串=库根）；未传默认选中库根
 * - settled：详情页数据是否已就绪（未就绪时提交按钮禁用）：防止详情未加载完就基于旧路径提交
 * - 行为：选择目录（单选，可展开）+ 可选"重命名"输入 → POST /assets/{id}/move；
 *   成功 toast + 关闭（失效 assets/asset-detail 由 useAssetMove 完成）；
 *   409 TARGET_EXISTS → apiErrorText 转"目标位置已有同名文件"（服务端错误码映射）。
 * - libraryId：M3 收尾后 AssetDetail 已携带 libraryId，调用方（DetailPage）
 *   优先传详情库 ID、加载瞬态用第一库兜底；未传时目录树查询禁用（协议必填）。
 */

import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { ScrollArea } from '@/components/ui/scroll-area'
import { useDirTree } from '@/hooks/use-dirs'
import { DirTree } from '@/components/organize/DirTree'
import { apiErrorText } from '@/pages/_shared/error-text'
import { useAssetMove } from '@/pages/_shared/use-asset-move'

const TEXT_TITLE = '移动 / 重命名'
const TEXT_DESCRIPTION = '选择目标目录（可选中库根）；填写重命名以同时改名，留空则仅移动'
const TEXT_TARGET_PLACEHOLDER = '重命名（可选）'
const TEXT_CONFIRM = '确认移动'
const TEXT_CANCEL = '取消'
const TEXT_SUCCESS = '移动成功'
const TEXT_LOADING_TREE = '目录加载中…'
const TEXT_TREE_FAILED = '目录加载失败，请重试'

export interface MoveDialogProps {
  /** 目标资产 ID */
  assetId: string
  /** 资产当前目录（库内相对路径；空串 = 库根）；未传默认选中库根 */
  defaultDir?: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 详情页数据是否已就绪：false 时提交按钮禁用（防未加载完基于旧路径提交） */
  settled?: boolean
  /** 目标库 ID（服务端 /dirs 必填锚定单一库）；未传时由调用方保证已选默认库 */
  libraryId?: string
}

export function MoveDialog({ assetId, defaultDir, open, onOpenChange, settled, libraryId }: MoveDialogProps) {
  const [selectedPath, setSelectedPath] = useState('')
  const [newName, setNewName] = useState('')
  const dirTree = useDirTree(libraryId)
  const move = useAssetMove()

  // 打开时以 defaultDir 初始化选中目录（每次打开重置：上一轮选择不残留）
  const handleOpenChange = (next: boolean) => {
    if (next) {
      setSelectedPath(defaultDir ?? '')
      setNewName('')
    }
    onOpenChange(next)
  }

  const handleSubmit = () => {
    move.mutate(
      {
        assetId,
        targetDir: selectedPath,
        // 空字符串 = 不重命名，不发送 newName 字段（协议字段可选）
        newName: newName.trim() === '' ? undefined : newName.trim(),
      },
      {
        onSuccess: () => {
          toast.success(TEXT_SUCCESS)
          onOpenChange(false)
        },
        onError: (error) => toast.error(apiErrorText(error)),
      },
    )
  }

  const treeReady = dirTree.isSuccess && dirTree.data
  const canSubmit = settled !== false && treeReady && !move.isPending

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{TEXT_TITLE}</DialogTitle>
          <DialogDescription>{TEXT_DESCRIPTION}</DialogDescription>
        </DialogHeader>
        <ScrollArea className="max-h-64 rounded-lg border border-[var(--qm-border)] p-2">
          {dirTree.isLoading && <p className="px-2 py-3 text-sm text-[var(--qm-text-muted)]">{TEXT_LOADING_TREE}</p>}
          {dirTree.isError && <p className="px-2 py-3 text-sm text-[var(--qm-text-muted)]">{TEXT_TREE_FAILED}</p>}
          {treeReady && (
            <DirTree root={dirTree.data} selectedPath={selectedPath} onSelect={setSelectedPath} />
          )}
        </ScrollArea>
        <Input
          value={newName}
          onChange={(event) => setNewName(event.target.value)}
          placeholder={TEXT_TARGET_PLACEHOLDER}
          disabled={!canSubmit || move.isPending}
        />
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={move.isPending}>
            {TEXT_CANCEL}
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit}>
            {move.isPending ? '…' : TEXT_CONFIRM}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
