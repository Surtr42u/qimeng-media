/**
 * 资产网格：纯容器（列数由父级传入——首页/全部页共用同一列数状态 use-grid-columns）。
 * 不带任何数据获取：父级负责把数据喂进来，组件只做排布（分层铁律）。
 */

import type { AssetSummary } from '@/api/generated'
import { AssetCard } from './AssetCard'
import { gridColumnsClass } from './use-grid-columns'

/** 网格间距（px）：与 token 节奏对齐（--qm-space-1 = 0.25rem） */
const GRID_GAP_CLASS = 'gap-[var(--qm-space-1)]'

interface AssetGridProps {
  items: AssetSummary[]
  columns: number
  onOpen: (asset: AssetSummary, index: number) => void
  /** 子集起始序号：日期分组时各组切片共享整批 index（详情批次序号依赖它），默认 0 */
  startIndex?: number
}

export function AssetGrid({ items, columns, onOpen, startIndex = 0 }: AssetGridProps) {
  return (
    <div className={`grid ${gridColumnsClass(columns)} ${GRID_GAP_CLASS}`}>
      {items.map((item, index) => (
        <AssetCard key={item.id ?? index} asset={item} index={startIndex + index} onOpen={onOpen} />
      ))}
    </div>
  )
}
