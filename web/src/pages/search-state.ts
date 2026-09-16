/**
 * 搜索页筛选状态机与档位常量（SearchPage/SearchFilters 共用；纯 TS 无组件，
 * 与 SearchFilters 拆开是为满足 oxlint only-export-components 的 Fast Refresh 约束）。
 */

/** 搜索筛选状态机（照 app.js SEARCH_STATE；tags 存协议 tagId 数组） */
export interface SearchFilterState {
  partition: string
  type: string
  sort: string
  order: string
  plays: string
  size: string
  time: string
  tagMode: string
  tags: string[]
  yearFrom: string
  yearTo: string
}

/** 排序/顺位/区间档位选项（文案=原型）。分区顺序=相册页 all/regular/cos 同款（用户 2026-09-04 拍板） */
export const PARTITION_OPTIONS = ['全部', '常规', 'COS'] as const
export const SORT_TABS = ['综合排序', '最多点击'] as const
export const ORDER_OPTIONS = ['降序', '升序'] as const
export const PLAYS_OPTIONS = ['全部', '未播放', '1-5', '5-20', '>20'] as const
export const SIZE_OPTIONS = ['全部', '<1MB', '1-10MB', '10-50MB', '>50MB'] as const
export const TIME_OPTIONS = ['全部', '今天', '本周', '本月', '近三月', '本年', '按年份区间'] as const
export const TAG_MODE_OPTIONS = ['模糊', '精确'] as const

// 当前年倒推 11 个年份（照 app.js SEARCH_YEARS；以当前年份滚动生成，免维护）
export const SEARCH_YEARS = Array.from({ length: 11 }, (_, i) => String(new Date().getFullYear() - i))

/** 初值与「新搜索重置」共用同一份（照原型 resetSearchState：新查询不继承旧筛选）。
 *  分区默认「全部」（2026-09-04 用户拍板：搜索默认常规∪COS——库内容大头是 COS，
 *  默认排除会让多数搜索词 0 结果；要隔离的用户手动切「常规」，DOMAIN_RULES §3 口径同步）。 */
export function newSearchState(): SearchFilterState {
  return {
    partition: '全部',
    type: '综合',
    sort: '综合排序',
    order: '降序',
    plays: '全部',
    size: '全部',
    time: '全部',
    tagMode: '模糊',
    tags: [],
    yearFrom: SEARCH_YEARS[SEARCH_YEARS.length - 1],
    yearTo: SEARCH_YEARS[0],
  }
}
