import type { DirTree } from '@/api/generated'
import { dirLabel } from '@/lib/format'

/**
 * 目录树共享渲染（W-1 抽取自 LibraryManagePage 的 DirNode）：
 * - DirBrowser（目录浏览卡）：只读模式——span 行，视觉与抽取前一致；
 * - UploadCard（上传卡）：选择模式——传 onSelect/selectedPath 时渲染按钮行，
 *   点击选中该目录作为上传目标。
 * 标签口径：取路径末段（协议 path 统一 '/' 分隔，兼容 Windows 反斜杠）；
 * 根节点（path = ''）无末段，显示「库根」（DirBrowser 根行原显示空串，统一为「库根」）。
 */

/** 目录树行缩进步长 px（与抽取前 DirNode 的 depth * 14 一致） */
const DIR_TREE_INDENT_PX = 14

export interface DirTreeNodesProps {
  node: DirTree
  depth: number
  /** 选择模式：当前选中目录（库内相对路径，'' = 库根） */
  selectedPath?: string
  /** 选择模式：点击回调（传了即视为选择模式，渲染可点按钮行） */
  onSelect?: (node: DirTree) => void
}

/** 递归节点行。fileCount = 该目录直接文件数（协议 GET /dirs 口径，不递归） */
export function DirTreeNodes({ node, depth, selectedPath, onSelect }: DirTreeNodesProps) {
  const path = node.path ?? ''
  const selectable = typeof onSelect === 'function'
  return (
    <li>
      {selectable ? (
        <button
          type="button"
          className={`dir-node${selectedPath === path ? ' selected' : ''}`}
          style={{ paddingLeft: DIR_TREE_INDENT_PX * depth + 8 }}
          onClick={() => onSelect?.(node)}
        >
          <span className="dir-name">{dirLabel(path)}</span>
          <span className="dir-count">{node.fileCount ?? 0} 文件</span>
        </button>
      ) : (
        <span className="rank-name" style={{ paddingLeft: depth * DIR_TREE_INDENT_PX }}>
          {dirLabel(path)}{' '}
          <b style={{ color: 'var(--text-sub)', fontWeight: 400 }}>{node.fileCount ?? 0} 文件</b>
        </span>
      )}
      {(node.dirs ?? []).length > 0 && (
        <ul style={{ listStyle: 'none' }}>
          {(node.dirs ?? []).map((child) => (
            <DirTreeNodes
              key={child.path}
              node={child}
              depth={depth + 1}
              selectedPath={selectedPath}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}
    </li>
  )
}
