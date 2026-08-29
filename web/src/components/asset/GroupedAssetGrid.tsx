/**
 * 日期分组网格：按 modifiedAt（mtime = DOMAIN_RULES §8 日期分组字段）折叠分组，
 * 组头标签用 lib/format.dateGroupLabel（今天/昨天/周X/yyyy-MM-dd/未知日期——规则唯一来源）。
 *
 * 为什么客户端分组：协议 groupByDate 参数被服务端忽略（M2 占位），日期分组由客户端承担
 * （任务事实清单：groupByDate 被服务端忽略——日期分组必须客户端做）。
 *
 * 组头吸顶说明（交互规格：sticky 顶部 + 背景区分滚动内容）：
 * 分组头 z-10、筛选用具区 z-30，吸顶位置 top-0/top-11 相互让位（见 AllAssetsPage 的
 * TOOLBAR_STICKY_TOP 常量，双方注释互指）。
 */

import type { AssetSummary } from '@/api/generated'
import { dateGroupLabel } from '@/lib/format'
import { AssetGrid } from './AssetGrid'

/** 分组头吸顶偏移（= 工具区高度 44px，若工具区高度变更须同步此值） */
const GROUP_HEADER_STICKY_TOP_CLASS = 'top-11'

/** 分组头样式：半透明表面 + 毛玻璃，滚动时与内容区分（交互规格） */
const GROUP_HEADER_CLASS =
  'sticky z-10 bg-[var(--qm-surface)]/90 py-[var(--qm-space-1)] text-sm font-bold text-[var(--qm-text-muted)] backdrop-blur'

/** 日期分组键（本地时区 yyyy-MM-dd；无效/缺失归入空键=未知日期组） */
function dateGroupKey(modifiedAt: string | undefined): string {
  const date = new Date(modifiedAt ?? '')
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

interface DateGroup {
  label: string
  items: AssetSummary[]
}

/** 分组（保持组间/组内相对顺序 = 服务端排序结果，只做折叠不重排） */
function groupByDate(items: AssetSummary[]): DateGroup[] {
  const groups = new Map<string, DateGroup>()
  for (const item of items) {
    const key = dateGroupKey(item.modifiedAt)
    const existing = groups.get(key)
    if (existing) {
      existing.items.push(item)
    } else {
      groups.set(key, { label: dateGroupLabel(item.modifiedAt), items: [item] })
    }
  }
  return [...groups.values()]
}

interface GroupedAssetGridProps {
  items: AssetSummary[]
  columns: number
  onOpen: (asset: AssetSummary, index: number) => void
}

export function GroupedAssetGrid({ items, columns, onOpen }: GroupedAssetGridProps) {
  const groups = groupByDate(items)
  let cursor = 0 // 遍历累计的全局 index（详情批次序号 = 整批数组序号）

  return (
    <div>
      {groups.map((group) => {
        const startIndex = cursor
        cursor += group.items.length
        return (
          <section key={group.label + startIndex}>
            <h2 className={`${GROUP_HEADER_STICKY_TOP_CLASS} ${GROUP_HEADER_CLASS}`}>{group.label}</h2>
            <AssetGrid items={group.items} columns={columns} onOpen={onOpen} startIndex={startIndex} />
          </section>
        )
      })}
    </div>
  )
}
