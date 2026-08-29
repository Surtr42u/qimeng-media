/**
 * 时间轴标签条（视频进度条上方一排横向滚动 chip，交互规格"时间轴标签"）。
 *
 * - chip 点击 = seek 到 timeMillis；
 * - 添加：打开弹窗前先暂停播放（保证标记时间点 = 画面当前时间），默认 timeMillis =
 *   当前 currentTime（可编辑），名称输入 + ❤️/⭐ 快捷按钮（直接以符号命名）；
 * - 删除：chip 悬停显示删除入口（与 LEGACY"长按删除"降级），确认后 DELETE；
 * - 增删后由 hook 的 invalidate 自动 refetch（useTimelineTags 的 mutation 已封装）。
 */

import { useRef, useState } from 'react'
import { Plus, X } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import {
  useTimelineTagCreate,
  useTimelineTagDelete,
  useTimelineTags,
} from '@/hooks/use-timeline-tags'
import { formatDuration } from '@/lib/format'

/* ------------------------------ 文案/常量（交互规格来源） ------------------------------ */

const TEXT_ADD = '标记'
const TEXT_DELETE_CONFIRM = '删除这个时间轴标签？'
const TEXT_DELETE = '删除'
const TEXT_NAME_PLACEHOLDER = '标签名称'
const TEXT_TIME_PLACEHOLDER = '时间（毫秒）'
const TEXT_ADD_SUCCESS = '已标记'
const TEXT_ADD_FAIL = '标记失败'
const TEXT_DELETE_SUCCESS = '已删除时间轴标签'
/** 快捷表情名称（交互规格：❤️ / ⭐ 快捷按钮，直接命名为符号本身） */
const QUICK_NAMES = ['❤️', '⭐'] as const

interface TimelineBarProps {
  assetId: string
  /** 视频元素（读 currentTime、seek、暂停用） */
  video: HTMLVideoElement | null
}

export function TimelineBar({ assetId, video }: TimelineBarProps) {
  const tagsQuery = useTimelineTags(assetId)
  const createTag = useTimelineTagCreate(assetId)
  const deleteTag = useTimelineTagDelete(assetId)

  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  /** 毫秒输入（弹窗打开瞬间从视频当前时间取值，可编辑） */
  const [timeMillis, setTimeMillis] = useState(0)
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)
  /** 添加弹窗打开时的视频时间（供快捷表情直接提交） */
  const timeAtOpenRef = useRef(0)

  const openAddDialog = () => {
    // 交互规格：打开弹窗前先暂停播放，保证标记时间点 = 画面时间
    video?.pause()
    const ms = Math.round((video?.currentTime ?? 0) * 1000)
    timeAtOpenRef.current = ms
    setTimeMillis(ms)
    setName('')
    setOpen(true)
  }

  const submit = async (finalName: string, finalTimeMs: number) => {
    try {
      await createTag.mutateAsync({ timeMillis: finalTimeMs, name: finalName.trim() })
      toast.success(TEXT_ADD_SUCCESS)
      setOpen(false)
    } catch {
      toast.error(TEXT_ADD_FAIL)
    }
  }

  const confirmDelete = async (id: string) => {
    try {
      await deleteTag.mutateAsync(id)
      toast.success(TEXT_DELETE_SUCCESS)
    } catch {
      // hook 已做 invalidate；失败保持现状
    }
  }

  const seek = (time: number) => {
    if (video) video.currentTime = time / 1000
  }

  const tags = tagsQuery.data ?? []

  return (
    <div className="flex items-center gap-1 overflow-x-auto pb-1" onClick={(e) => e.stopPropagation()}>
      {tags.map((tag) => {
        const tagId = tag.id ?? ''
        const ms = tag.timeMillis ?? 0
        return (
          <span key={tagId} className="group relative flex shrink-0">
            <button
              type="button"
              onClick={() => seek(ms)}
              className="rounded-md bg-[var(--qm-surface)]/90 px-2 py-0.5 text-xs text-[var(--qm-text-muted)] backdrop-blur hover:bg-[var(--qm-surface-soft)]"
              title={`${formatDuration(ms)} · ${tag.name ?? ''}`}
            >
              {formatDuration(ms)} {tag.name}
            </button>
            {/* 悬停显示删除入口（触屏无 hover：依赖长按的降级方案，点击后确认弹窗） */}
            <span className="absolute -top-1 -right-1 hidden group-hover:block">
              <button
                type="button"
                aria-label="删除时间轴标签"
                onClick={() => setDeleteTarget({ id: tagId, name: tag.name ?? '' })}
                className="rounded-full bg-[var(--qm-destructive)] p-0.5 text-white"
              >
                <X className="size-3" aria-hidden />
              </button>
            </span>
          </span>
        )
      })}
      <Button size="xs" variant="secondary" onClick={openAddDialog} className="shrink-0">
        <Plus className="size-3" aria-hidden />
        {TEXT_ADD}
      </Button>

      {/* 添加弹窗：名称 + 时间（毫秒）+ 快捷表情 */}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-[90vw]">
          <DialogHeader>
            <DialogTitle>{TEXT_ADD}</DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={TEXT_NAME_PLACEHOLDER}
              autoFocus
            />
            <Input
              type="number"
              value={timeMillis}
              onChange={(e) => setTimeMillis(Number(e.target.value))}
              placeholder={TEXT_TIME_PLACEHOLDER}
            />
            <div className="flex gap-2">
              {QUICK_NAMES.map((quick) => (
                <Button
                  key={quick}
                  size="sm"
                  variant="secondary"
                  disabled={createTag.isPending}
                  onClick={() => void submit(quick, timeAtOpenRef.current)}
                >
                  {quick}
                </Button>
              ))}
            </div>
          </div>
          <DialogFooter>
            <Button
              disabled={name.trim() === '' || createTag.isPending}
              onClick={() => void submit(name, timeMillis)}
            >
              {TEXT_ADD}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除确认 */}
      <Dialog open={deleteTarget !== null} onOpenChange={(o) => !o && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{`${TEXT_DELETE_CONFIRM}（${deleteTarget?.name ?? ''}）`}</DialogTitle>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteTarget(null)}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={deleteTag.isPending}
              onClick={() => {
                if (deleteTarget) void confirmDelete(deleteTarget.id)
                setDeleteTarget(null)
              }}
            >
              {TEXT_DELETE}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
