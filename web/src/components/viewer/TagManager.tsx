/**
 * 标签管理弹窗（详情页"标签"按钮 → Sheet，交互规格"标签弹窗"）。
 *
 * 分组：
 * - 当前标签（AssetDetail.tags，**按 API 顺序渲染 = 服务端已按关联时间倒序 = 最近添加置顶**，
 *   协议承诺的排序约定——客户端不再排序）：实底 Badge + 关闭图标 → PUT 整体替换（去掉该 id）；
 * - 其他标签（useTags，服务端名字序）减去当前集：软底 Badge，点击即添加（整体替换 = 当前 + 该 id，
 *   置顶由服务端按关联时间完成）；
 * - 顶部输入：新建标签 POST /tags 成功后立即加入当前集；409（重名）时切换到现有同名标签加入；
 * - 全局删除（DELETE /tags/{id}，服务端级联清关联）只在"其他标签"区域提供（hover 显示删除入口，
 *   LEGACY"长按删除"的 web 降级）；当前标签区只解除关联不全局删。
 * - 增删反馈：hook 的 onSuccess 都会 invalidate detail/assets/tags——列表/详情/标签源自动同步。
 */

import { useMemo, useState } from 'react'
import { Plus, Trash2, X } from 'lucide-react'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import type { AssetDetail, Tag } from '@/api/generated'
import { useAssetTagsReplace, useTagCreate, useTagDelete, useTags } from '@/hooks/use-tags'
import { cn } from '@/lib/utils'

/* ------------------------------ 文案常量（交互规格来源） ------------------------------ */

const TEXT_SECTION_CURRENT = '当前标签'
const TEXT_SECTION_OTHER = '其他标签'
const TEXT_INPUT_PLACEHOLDER = '输入新标签名，回车或点添加'
const TEXT_ADD = '添加'
const TEXT_DELETE_CONFIRM = '删除该标签将同步移除所有媒体上的此标签（不可恢复）。确定删除？'
const TEXT_DELETE = '删除'
const TEXT_CANCEL = '取消'
const TEXT_DELETE_TITLE = '删除标签'
const TEXT_CREATE_FAIL = '创建标签失败'
const TEXT_REPLACE_FAIL = '更新标签失败'

interface TagManagerProps {
  assetId: string
  /** 当前资产详情（tags 数据源；未加载完成时按钮禁用） */
  detail: AssetDetail | undefined
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function TagManager({ assetId, detail, open, onOpenChange }: TagManagerProps) {
  const tagsQuery = useTags()
  const createTag = useTagCreate()
  const deleteTag = useTagDelete()
  const replaceTags = useAssetTagsReplace(assetId)

  const [newName, setNewName] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<Tag | null>(null)

  const currentTags = detail?.tags ?? []
  const currentIds = useMemo(
    () => new Set(currentTags.map((tag) => tag.id ?? '').filter(Boolean)),
    [currentTags],
  )
  const otherTags = (tagsQuery.data ?? []).filter((tag) => !currentIds.has(tag.id ?? ''))
  const allTags = tagsQuery.data ?? []

  /** 整体替换（当前集 ± 某个 id） */
  const replaceWith = async (nextIds: string[]) => {
    try {
      await replaceTags.mutateAsync(nextIds)
    } catch {
      toast.error(TEXT_REPLACE_FAIL)
    }
  }

  /** 解除关联：当前集去掉该 id */
  const detachTag = (tagId: string) => {
    void replaceWith([...currentTags].filter((t) => (t.id ?? '') !== tagId).map((t) => t.id ?? ''))
  }

  /** 添加其他标签：当前集 + 该 id（既有顺序不变，新标签由服务端置顶） */
  const attachTag = (tagId: string) => {
    void replaceWith([...currentIds, tagId])
  }

  /** 新建标签 → 立即加入当前集；409 重名时复用现有同名标签 */
  const handleCreate = async () => {
    const name = newName.trim()
    if (!name || replaceTags.isPending || createTag.isPending) return
    const existing = allTags.find((tag) => (tag.name ?? '') === name)
    let tagId = existing?.id
    if (!tagId) {
      try {
        tagId = (await createTag.mutateAsync(name)).id
      } catch {
        // 409 重名：tags 列表尚未刷新时再查一次（useTags invalidate 后 refetch）
        const reloaded = tagsQuery.data?.find((tag) => (tag.name ?? '') === name)?.id
        if (!reloaded) {
          toast.error(TEXT_CREATE_FAIL)
          return
        }
        tagId = reloaded
      }
    }
    if (tagId) {
      setNewName('')
      await replaceWith([...currentIds, tagId])
    }
  }

  const confirmGlobalDelete = async () => {
    if (!deleteTarget?.id) return
    try {
      await deleteTag.mutateAsync(deleteTarget.id)
      setDeleteTarget(null)
    } catch {
      // hook 已 invalidate；失败保持现状
    }
  }

  const isBusy = replaceTags.isPending || createTag.isPending

  return (
    <>
      <Sheet open={open} onOpenChange={onOpenChange}>
        <SheetContent side="bottom" className="max-h-[80dvh] gap-4 overflow-y-auto">
          <SheetHeader>
            <SheetTitle>标签</SheetTitle>
          </SheetHeader>

          {/* 新建标签 */}
          <div className="flex gap-2">
            <Input
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              placeholder={TEXT_INPUT_PLACEHOLDER}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void handleCreate()
              }}
            />
            <Button onClick={() => void handleCreate()} disabled={newName.trim() === '' || isBusy}>
              <Plus className="size-4" aria-hidden />
              {TEXT_ADD}
            </Button>
          </div>

          {/* 当前标签：实底（主色）+ 关闭图标（解除关联） */}
          <div className="flex flex-col gap-2">
            <h3 className="text-xs font-semibold text-[var(--qm-text-muted)]">{TEXT_SECTION_CURRENT}</h3>
            <div className="flex flex-wrap gap-2">
              {currentTags.length === 0 ? (
                <span className="text-xs text-[var(--qm-text-muted)]">暂无标签</span>
              ) : (
                currentTags.map((tag) => {
                  const tagId = tag.id ?? ''
                  return (
                    <Badge key={tagId} variant="default" className="h-[var(--qm-chip-height)] gap-1 px-2.5 text-sm">
                      {tag.name}
                      <button
                        type="button"
                        aria-label={`移除标签 ${tag.name ?? ''}`}
                        disabled={isBusy}
                        onClick={() => detachTag(tagId)}
                        className="rounded-full p-0.5 text-[var(--qm-primary-foreground)] hover:bg-white/20"
                      >
                        <X className="size-3" aria-hidden />
                      </button>
                    </Badge>
                  )
                })
              )}
            </div>
          </div>

          {/* 其他标签：软底 chip，点击添加；hover 显示全局删除入口 */}
          <div className="flex flex-col gap-2">
            <h3 className="text-xs font-semibold text-[var(--qm-text-muted)]">{TEXT_SECTION_OTHER}</h3>
            <div className="flex flex-wrap gap-2">
              {otherTags.length === 0 ? (
                <span className="text-xs text-[var(--qm-text-muted)]">没有其他标签了</span>
              ) : (
                otherTags.map((tag) => {
                  const tagId = tag.id ?? ''
                  const tagName = tag.name ?? ''
                  return (
                    <span key={tagId} className="group/other relative inline-flex">
                      <Badge
                        variant="secondary"
                        role="button"
                        tabIndex={0}
                        aria-pressed={false}
                        className="h-[var(--qm-chip-height)] cursor-pointer px-2.5 text-sm"
                        onClick={() => attachTag(tagId)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            attachTag(tagId)
                          }
                        }}
                      >
                        {tagName}
                        {tag.fileCount != null ? ` (${tag.fileCount})` : ''}
                      </Badge>
                      <button
                        type="button"
                        aria-label={`删除标签 ${tagName}`}
                        onClick={(e) => {
                          e.stopPropagation()
                          setDeleteTarget(tag)
                        }}
                        className={cn(
                          'absolute -top-2 -right-2 rounded-full bg-[var(--qm-destructive)] p-1 text-white',
                          'opacity-0 transition-opacity group-hover/other:opacity-100',
                        )}
                      >
                        <Trash2 className="size-3" aria-hidden />
                      </button>
                    </span>
                  )
                })
              )}
            </div>
          </div>
        </SheetContent>
      </Sheet>

      {/* 全局删除确认（服务端级联清关联，不可恢复——交互规格） */}
      <Dialog open={deleteTarget !== null} onOpenChange={(o) => !o && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{TEXT_DELETE_TITLE}</DialogTitle>
            <p className="text-sm text-[var(--qm-text-muted)]">{TEXT_DELETE_CONFIRM}</p>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              {TEXT_CANCEL}
            </Button>
            <Button variant="destructive" disabled={deleteTag.isPending} onClick={() => void confirmGlobalDelete()}>
              {TEXT_DELETE}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
