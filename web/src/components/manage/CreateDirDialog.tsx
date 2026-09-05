import { useState } from 'react'
import { Dialog } from 'radix-ui'
import { toast } from 'sonner'
import { FolderPlus } from 'lucide-react'
import { useCreateDir } from '@/hooks/use-file-ops'
import { dirLabel } from '@/lib/format'

/**
 * 新建子目录弹窗（B-1 文件管理操作化）：在指定父目录下输入名称创建，
 * POST /dirs {path: 父路径/名称, libraryId}——幂等，已存在同样成功。
 * 弹窗按 open 条件挂载 + useState 惰性初始化，打开即复位输入（同
 * AssetTagRow 的 TagDialog 模式，规避 set-state-in-effect）。
 * 样式复用 .detail-dialog 族（radix Dialog 统一包，prototype.css 追加段）。
 */
export interface CreateDirDialogProps {
  open: boolean
  libraryId: string
  /** 父目录（库内相对路径，'' = 库根） */
  parentPath: string
  onClose: () => void
}

export function CreateDirDialog({ open, libraryId, parentPath, onClose }: CreateDirDialogProps) {
  const [name, setName] = useState('')
  const createDir = useCreateDir()
  const trimmed = name.trim()

  const submit = (): void => {
    if (!trimmed) return
    createDir.mutate(
      // 完整相对路径 = 父路径拼接名称（协议 path 语义：库内任意层级）
      { libraryId, path: parentPath === '' ? trimmed : `${parentPath}/${trimmed}` },
      {
        onSuccess: () => {
          toast.success(`已在「${dirLabel(parentPath)}」下创建目录「${trimmed}」`)
          onClose()
        },
        onError: (err) =>
          toast.error(`创建目录失败：${err instanceof Error ? err.message : String(err)}`),
      },
    )
  }

  if (!open) return null

  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="detail-dialog-overlay" />
        <Dialog.Content className="detail-dialog">
          <Dialog.Title className="detail-dialog-title">新建子目录</Dialog.Title>
          <Dialog.Description className="detail-dialog-desc">
            在「{dirLabel(parentPath)}」下创建目录；同名目录已存在时同样成功（幂等）。
          </Dialog.Description>
          <div className="tag-newrow" style={{ marginTop: 14 }}>
            <input
              className="tag-input"
              value={name}
              placeholder="目录名，回车确认"
              autoFocus
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') submit()
              }}
            />
            <button
              className="confirm-btn confirm-btn--cancel"
              onClick={submit}
              disabled={!trimmed || createDir.isPending}
              title="新建子目录"
            >
              <FolderPlus width={14} height={14} />
              创建
            </button>
          </div>
          <div className="detail-dialog-foot">
            <button className="confirm-btn confirm-btn--cancel" onClick={onClose}>
              取消
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
