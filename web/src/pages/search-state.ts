/**
 * 搜索页特有筛选状态（分区/类型 tab 两个页面维；纯 TS 无组件）。
 * 面板共用档位（排序/顺位/播放次数/文件大小/时间范围/标签模式/标签）已于
 * 2026-09-17 面板内容两页对齐时抽到 lib/panel-filters.ts + hooks/use-panel-filters.ts，
 * 本文件只剩搜索页自己的状态与初值。
 */

/** 搜索页特有维状态（面板七行走 usePanelFilters，不在此重复） */
export interface SearchPageState {
  partition: string
  type: string
}

/** 分区三档选项（文案=原型）。顺序=相册页 all/regular/cos 同款（用户 2026-09-04 拍板） */
export const PARTITION_OPTIONS = ['全部', '常规', 'COS'] as const

/** 搜索页特有维初值与新搜索重置共用同一份（照原型 resetSearchState：新查询不继承旧筛选）。
 *  分区默认「全部」（2026-09-04 用户拍板：搜索默认常规∪COS——库内容大头是 COS，
 *  默认排除会让多数搜索词 0 结果；要隔离的用户手动切「常规」，DOMAIN_RULES §3 口径同步）。 */
export function newSearchPageState(): SearchPageState {
  return { partition: '全部', type: '综合' }
}
