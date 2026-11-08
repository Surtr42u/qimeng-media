import type { AssetSummary, CountRange, SizeRange } from '@/api/generated'

/**
 * 搜索页「原型文案档位 → 协议参数」映射（2026-09-07 自 SearchPage 抽离——
 * ADR-0008 业务规则不内嵌页面组件）。原型的筛选文案（未播放/1-5/>20 等）是
 * UI 词汇，协议要的是枚举值——这层翻译集中在此单源，页面与筛选面板共享。
 * 纯函数/纯表：不碰 IO。
 */

/** 播放/浏览次数档（文案=原型）→ 协议 CountRange；边界以 browse.sql 为准：none=0/low=1-5/mid=5-20/high=>20 */
export const PLAYS_TO_RANGE: Record<string, CountRange | undefined> = {
  全部: undefined,
  未播放: 'none',
  '1-5': 'low',
  '5-20': 'mid',
  '>20': 'high',
}

/** 文件大小档（文案=原型）→ 协议 SizeRange；边界以 browse.sql 为准：lt1m<1MB/m1to10<10MB/m10to50<50MB/gt50m */
export const SIZE_TO_RANGE: Record<string, SizeRange | undefined> = {
  全部: undefined,
  '<1MB': 'lt1m',
  '1-10MB': 'm1to10',
  '10-50MB': 'm10to50',
  '>50MB': 'gt50m',
}

/** 本地日历日 → yyyy-MM-dd（服务端语义：本地日历日，dateTo 含当日全天——assets.go newAssetFilters） */
function localDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * 搜索结果卡标题：完整文件名优先，COS 作品名只作回退。
 *
 * 搜索（2026-11-07 语义修订）按文件名/目录/作者/标签等多字段匹配，纯数字词与
 * 组图页码都在文件名里；卡片必须让用户对认「为什么命中」——COS 资产此前
 * 标题取 cosWork，作品编号/组图页码（如「朱诺 11 (2).png」）不可见。这里只做
 * 展示取值，不重写任何匹配规则。文件名/作品名都缺失的存量返回空串，由调用方
 * 决定空标题呈现。
 */
export function searchResultTitle(asset: Pick<AssetSummary, 'fileName' | 'cosWork'>): string {
  return asset.fileName?.trim() || asset.cosWork?.trim() || ''
}

/** 分区文案 → useAssetsTotal 的分区 key（列表参数的三态组装在 listParams 唯一组装点内联） */
export function partitionKey(p: string): 'regular' | 'cos' | 'all' {
  return p === 'COS' ? 'cos' : p === '全部' ? 'all' : 'regular'
}

/** 时间范围档 → dateFrom/dateTo（「全部」/「按年份区间」不产日期，年份走 yearFrom/yearTo） */
export function dateRangeFor(time: string): { dateFrom?: string; dateTo?: string } | undefined {
  const today = new Date()
  const to = localDate(today)
  if (time === '今天') return { dateFrom: to, dateTo: to }
  if (time === '本周') {
    const monday = new Date(today)
    monday.setDate(today.getDate() - ((today.getDay() + 6) % 7)) // 周一为一周之始（周日 getDay()=0 → 回 6 天）
    return { dateFrom: localDate(monday), dateTo: to }
  }
  if (time === '本月') {
    const first = new Date(today.getFullYear(), today.getMonth(), 1)
    return { dateFrom: localDate(first), dateTo: to }
  }
  if (time === '近三月') {
    const from = new Date(today)
    from.setMonth(today.getMonth() - 3)
    return { dateFrom: localDate(from), dateTo: to }
  }
  if (time === '本年') return { dateFrom: `${today.getFullYear()}-01-01`, dateTo: to }
  return undefined
}
