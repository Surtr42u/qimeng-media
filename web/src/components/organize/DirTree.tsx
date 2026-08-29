/**
 * 目录树（整理页与 MoveDialog 共用）：单选高亮，根节点显示"／库根"，角标=直接子文件数。
 *
 * 数据纯渲染：根节点来自协议 GET /api/v1/dirs（DirTree.path 根为空串，子节点为库内相对路径），
 * 选中/提交逻辑由调用方（OrganizePage / MoveDialog）编排。
 */

import { Folder, FolderOpen } from 'lucide-react'
import type { DirTree } from '@/api/generated'
import { Badge } from '@/components/ui/badge'
import { ROOT_DIR_LABEL } from '@/pages/_shared/labels'
import { cn } from '@/lib/utils'

export interface DirTreeProps {
  /** 协议目录树（根节点 path=""，fileCount=库根直接子文件数） */
  root: DirTree
  /** 当前选中的库内相对路径（空串 = 库根） */
  selectedPath: string
  onSelect: (path: string) => void
}

/** 节点显示名：根=库根（路径空串），其余取路径最后一段（深层路径可读） */
function displayLabel(path: string): string {
  if (path === '') return ROOT_DIR_LABEL
  const segments = path.split('/')
  return segments[segments.length - 1] ?? path
}

interface DirNodeViewProps {
  node: DirTree
  depth: number
  selectedPath: string
  onSelect: (path: string) => void
}

/** 递归节点（path 空串 = 库根节点） */
function DirNodeView({ node, depth, selectedPath, onSelect }: DirNodeViewProps) {
  const path = node.path ?? ''
  const selected = path === selectedPath
  return (
    <>
      <button
        type="button"
        className={cn(
          'flex w-full items-center gap-1.5 rounded-lg py-1.5 pr-2 text-left text-sm transition-colors',
          selected
            ? 'bg-[var(--qm-primary-soft)] font-medium text-[var(--qm-primary)]'
            : 'text-foreground hover:bg-[var(--qm-accent)]',
        )}
        style={{ paddingLeft: `calc(var(--qm-space-3) * ${depth})` }}
        onClick={() => onSelect(path)}
        aria-current={selected || undefined}
      >
        {selected ? (
          <FolderOpen className="size-4 shrink-0" aria-hidden />
        ) : (
          <Folder className="size-4 shrink-0 text-[var(--qm-text-muted)]" aria-hidden />
        )}
        <span className="min-w-0 flex-1 truncate">{displayLabel(path)}</span>
        {/* 角标 = 直接子文件数（协议口径：不递归，子目录计数在各节点上） */}
        <Badge variant="secondary" className="shrink-0 font-normal">
          {node.fileCount ?? 0}
        </Badge>
      </button>
      {(node.dirs ?? []).map((child) => (
        <DirNodeView
          key={child.path ?? ''}
          node={child}
          depth={depth + 1}
          selectedPath={selectedPath}
          onSelect={onSelect}
        />
      ))}
    </>
  )
}

export function DirTree({ root, selectedPath, onSelect }: DirTreeProps) {
  return (
    <div className="flex flex-col gap-0.5">
      <DirNodeView node={root} depth={0} selectedPath={selectedPath} onSelect={onSelect} />
    </div>
  )
}
