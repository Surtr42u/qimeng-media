import type { ReactNode } from 'react'
import type { DirTree } from '@/api/generated'
import { dirLabel } from '@/lib/format'

/**
 * 目录树共享渲染（W-1 抽取自 LibraryManagePage 的 DirNode）：
 * - DirBrowser（目录浏览卡）：只读模式——span 行，视觉与抽取前一致；
 *   B-1 起可选传 renderActions，目录行升级为 flex 行容器（hover 显示行尾操作）；
 *   B-5 起可同时传 onSelect + renderActions（组合模式）：行内 select 按钮 +
 *   行尾操作并存（选中目录 → 树下列文件行）；
 * - UploadCard / MoveDialog（上传卡/整理弹窗）：选择模式——传 onSelect/
 *   selectedPath 时渲染按钮行，点击选中该目录作为目标（两弹窗只传选择、
 *   不传 renderActions，行内已有选中语义，不混入操作）。
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
  /** 只读模式的行尾操作区（B-1：目录浏览卡 hover 显示「新建子目录」）。
   *  只影响只读分支；选择模式（上传/整理弹窗）行内已有选中语义，不混入操作 */
  renderActions?: (node: DirTree) => ReactNode
}

/** 递归节点行。fileCount = 该目录直接文件数（协议 GET /dirs 口径，不递归） */
export function DirTreeNodes({ node, depth, selectedPath, onSelect, renderActions }: DirTreeNodesProps) {
  const path = node.path ?? ''
  const selectable = typeof onSelect === 'function'
  // 组合模式（B-5 DirBrowser）：select 按钮（选中态视觉同选择模式）+ 行尾
  // 操作并存——目录既是「选中看文件」入口又保留「新建子目录」操作。按钮
  // 不能嵌套按钮，故外层用 .dir-row flex 容器（视觉对齐 B-1 只读+操作行）
  const row = selectable && renderActions
  return (
    <li>
      {row ? (
        <div className="dir-row" style={{ paddingLeft: depth * DIR_TREE_INDENT_PX }}>
          <button
            type="button"
            className={`dir-node${selectedPath === path ? ' selected' : ''}`}
            onClick={() => onSelect?.(node)}
          >
            <span className="dir-name">{dirLabel(path)}</span>
            <span className="dir-count">{node.fileCount ?? 0} 文件</span>
          </button>
          <span className="dir-actions">{renderActions(node)}</span>
        </div>
      ) : selectable ? (
        <button
          type="button"
          className={`dir-node${selectedPath === path ? ' selected' : ''}`}
          style={{ paddingLeft: DIR_TREE_INDENT_PX * depth + 8 }}
          onClick={() => onSelect?.(node)}
        >
          <span className="dir-name">{dirLabel(path)}</span>
          <span className="dir-count">{node.fileCount ?? 0} 文件</span>
        </button>
      ) : renderActions ? (
        // 只读行 + 操作（B-1）：行容器 flex，标签/计数沿用原 span 视觉
        <div className="dir-row" style={{ paddingLeft: depth * DIR_TREE_INDENT_PX }}>
          <span className="rank-name">
            {dirLabel(path)}{' '}
            <b style={{ color: 'var(--text-sub)', fontWeight: 400 }}>{node.fileCount ?? 0} 文件</b>
          </span>
          <span className="dir-actions">{renderActions(node)}</span>
        </div>
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
              renderActions={renderActions}
            />
          ))}
        </ul>
      )}
    </li>
  )
}
