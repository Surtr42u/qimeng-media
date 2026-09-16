/**
 * 「更多筛选」面板共用档位与状态（2026-09-17 用户拍板：搜索页与相册页的
 * 面板内容对齐同构——首行=排序三档（默认/观看次数/文件大小，搜索页原
 * 「综合排序/最多点击」排序行随对齐撤除），其后 顺位/播放次数/文件大小/
 * 时间范围/标签模式/标签 六行；分区/类型等页面特有维留在各页不进本文件）。
 * 纯 TS 无组件、不碰 IO（ADR-0008：档位口径单源，业务规则不内嵌页面组件）。
 */

/** 排序三档（文案与手机端任务1/相册页逐字一致；「默认」=default 即时间排序） */
export const SORT_OPTIONS = ['默认', '观看次数', '文件大小'] as const
export type SortOption = (typeof SORT_OPTIONS)[number]

export const ORDER_OPTIONS = ['降序', '升序'] as const
export const PLAYS_OPTIONS = ['全部', '未播放', '1-5', '5-20', '>20'] as const
export const SIZE_OPTIONS = ['全部', '<1MB', '1-10MB', '10-50MB', '>50MB'] as const
export const TIME_OPTIONS = ['全部', '今天', '本周', '本月', '近三月', '本年', '按年份区间'] as const
export const TAG_MODE_OPTIONS = ['模糊', '精确'] as const

// 当前年倒推 11 个年份（照 app.js SEARCH_YEARS；以当前年份滚动生成，免维护）
export const SEARCH_YEARS = Array.from({ length: 11 }, (_, i) => String(new Date().getFullYear() - i))

/** 面板共用筛选状态（面板七行的档位值；标签存协议 tagId 数组） */
export interface PanelFilterState {
  sort: SortOption
  order: string
  plays: string
  size: string
  time: string
  tagMode: string
  tags: string[]
  yearFrom: string
  yearTo: string
}

/** 面板初值（两页共用；「新搜索重置」也回落到这一份，不继承旧筛选） */
export function newPanelState(): PanelFilterState {
  return {
    sort: '默认',
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

/** 面板是否有非缺省筛选生效（相册页「更多筛选」钮点亮口径）。
 *  标签模式单独切换不算——与列表口径一致（tagMode 仅随选中的 tagIds 提交） */
export function isPanelActive(s: PanelFilterState): boolean {
  return (
    s.sort !== '默认' ||
    s.order !== '降序' ||
    s.plays !== '全部' ||
    s.size !== '全部' ||
    s.time !== '全部' ||
    s.tags.length > 0
  )
}
