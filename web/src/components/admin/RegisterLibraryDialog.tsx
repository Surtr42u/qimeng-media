/**
 * 注册媒体库对话框（管理页库管理区）：name + rootPath 必填。
 * 错误文案走服务端错误码映射（PATH_NOT_FOUND/DATA_DIR_CONFLICT/CONFLICT...，见 _shared/error-text.ts）。
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
import { useLibrariesCreate } from '@/hooks/use-libraries'
import { apiErrorText } from '@/pages/_shared/error-text'

const TEXT_TITLE = '注册媒体库'
const TEXT_DESCRIPTION = '库目录为服务端可访问的绝对路径（禁止位于数据目录内，服务端校验）'
const TEXT_NAME_LABEL = '库名称'
const TEXT_NAME_PLACEHOLDER = '如：我的动漫库'
const TEXT_PATH_LABEL = '根目录（服务端路径）'
const TEXT_PATH_PLACEHOLDER = '如：/mnt/media/anime'
const TEXT_CONFIRM = '注册'
const TEXT_CANCEL = '取消'
const TEXT_SUCCESS = '库注册成功'

export interface RegisterLibraryDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function RegisterLibraryDialog({ open, onOpenChange }: RegisterLibraryDialogProps) {
  const [name, setName] = useState('')
  const [rootPath, setRootPath] = useState('')
  const create = useLibrariesCreate()

  const handleSubmit = () => {
    const trimmedName = name.trim()
    const trimmedPath = rootPath.trim()
    if (trimmedName === '' || trimmedPath === '') return
    create.mutate(
      { name: trimmedName, rootPath: trimmedPath },
      {
        onSuccess: () => {
          toast.success(TEXT_SUCCESS)
          setName('')
          setRootPath('')
          onOpenChange(false)
        },
        onError: (error) => toast.error(apiErrorText(error)),
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{TEXT_TITLE}</DialogTitle>
          <DialogDescription>{TEXT_DESCRIPTION}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs text-[var(--qm-text-muted)]">{TEXT_NAME_LABEL}</span>
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder={TEXT_NAME_PLACEHOLDER}
              disabled={create.isPending}
            />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs text-[var(--qm-text-muted)]">{TEXT_PATH_LABEL}</span>
            <Input
              value={rootPath}
              onChange={(event) => setRootPath(event.target.value)}
              placeholder={TEXT_PATH_PLACEHOLDER}
              disabled={create.isPending}
            />
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={create.isPending}>
            {TEXT_CANCEL}
          </Button>
          <Button onClick={handleSubmit} disabled={create.isPending || name.trim() === '' || rootPath.trim() === ''}>
            {create.isPending ? '…' : TEXT_CONFIRM}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
