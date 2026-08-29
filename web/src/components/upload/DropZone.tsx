/**
 * 上传拖拽区：拖拽（dragover/drop 阻止默认跳转 + 高亮反馈）+ 点击（input[type=file] multiple）。
 * 纯收集文件转交 onFiles，上传调度由 useUploadQueue 负责（本组件零业务规则）。
 */

import { useRef, useState } from 'react'
import { UploadCloud } from 'lucide-react'
import { cn } from '@/lib/utils'

const TEXT_DROP_HINT = '拖拽文件到此处，或点击选择文件'
const TEXT_CLICK_HINT = '支持多选；上传目标为当前选中目录'

export interface DropZoneProps {
  /** 拖放/选择产生的文件（不做类型过滤，白名单校验在服务端四道关卡） */
  onFiles: (files: File[]) => void
  /** 无库/未选目录等场景禁用交互 */
  disabled?: boolean
}

export function DropZone({ onFiles, disabled = false }: DropZoneProps) {
  const [dragging, setDragging] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  return (
    <div
      // 拖拽高亮：边框与底色用 token（--qm-border/--qm-primary-soft），dragging 状态主题联动
      className={cn(
        'flex flex-col items-center justify-center gap-[var(--qm-space-2)] rounded-xl border-2 border-dashed px-6 py-10 text-center transition-[border-color,background-color] duration-[var(--qm-duration-base)]',
        dragging
          ? 'border-[var(--qm-primary)] bg-[var(--qm-primary-soft)]'
          : 'border-[var(--qm-border)] bg-[var(--qm-surface-soft)]',
        disabled && 'pointer-events-none opacity-50',
      )}
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-label={TEXT_DROP_HINT}
      onClick={() => inputRef.current?.click()}
      onKeyDown={(event) => {
        if (!disabled && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault()
          inputRef.current?.click()
        }
      }}
      onDragOver={(event) => {
        // 阻止默认否则 drop 会触发浏览器打开文件；dragover 必须持续 preventDefault 才能进入 drop 态
        event.preventDefault()
        setDragging(true)
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        event.preventDefault()
        setDragging(false)
        if (disabled) return
        const files = Array.from(event.dataTransfer.files)
        if (files.length > 0) onFiles(files)
      }}
    >
      <UploadCloud className="size-8 text-[var(--qm-text-muted)]" aria-hidden />
      <p className="text-sm font-medium">{TEXT_DROP_HINT}</p>
      <p className="text-xs text-[var(--qm-text-muted)]">{TEXT_CLICK_HINT}</p>
      <input
        ref={inputRef}
        type="file"
        multiple
        className="hidden"
        disabled={disabled}
        onChange={(event) => {
          const files = Array.from(event.target.files ?? [])
          if (files.length > 0) onFiles(files)
          // 同一文件再次选择也触发 change：选完后清空 value
          event.target.value = ''
        }}
      />
    </div>
  )
}
