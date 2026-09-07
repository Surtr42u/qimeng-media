import type { ReactNode } from 'react'

/**
 * 多选态工具行（F6 文件管理页 DirFileList / 回收站页 TrashPage 共用；代码卫生 6：
 * 工具行结构两页同构——[全选] [已选计数] [调用方动作按钮] [退出多选]——第 2 次
 * 出现即抽共享组件）。locked（批量执行中）时全选/退出冻结：执行期禁改选集，
 * 退出会连带清空选集，故一并冻结。零 API 依赖（铁律 7），纯展示编排。
 */
export interface MultiSelectBarProps {
  selectedCount: number
  totalCount: number
  allSelected: boolean
  /** 批量执行中：全选/退出冻结（动作按钮由调用方自行 disabled） */
  locked: boolean
  /** 全选钮文案（文件管理页=「全选本目录」，回收站页=「全选」） */
  selectAllLabel: string
  onToggleAll: () => void
  onExit: () => void
  /** 页面专属动作按钮（批量删除/移动/恢复等） */
  children?: ReactNode
}

export function MultiSelectBar({
  selectedCount,
  totalCount,
  allSelected,
  locked,
  selectAllLabel,
  onToggleAll,
  onExit,
  children,
}: MultiSelectBarProps) {
  return (
    <div className="ms-bar">
      <button className="pill" type="button" disabled={locked} onClick={onToggleAll}>
        {allSelected ? '取消全选' : selectAllLabel}
      </button>
      <span className="ms-bar-count">
        已选 <b>{selectedCount}</b> / {totalCount}
      </span>
      {children}
      <button
        className="pill ms-bar-exit"
        type="button"
        disabled={locked}
        title="退出多选（Esc）"
        onClick={onExit}
      >
        退出多选
      </button>
    </div>
  )
}
