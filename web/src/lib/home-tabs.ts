/**
 * 首页顶栏分类 tab 与排行榜周期的共享定义（TopBar 与 HomePage 双消费：
 * tab/周期全部收敛到 URL 参数，TopBar 只写、HomePage 只读——单一事实源）。
 * 原型语义：推荐/cos/排行榜三 tab；hot 下顶栏渲染周期行（日/月/周/年，
 * 文案与顺序 = 原型 rank-panel）。
 */

/** 顶栏分类 tab（key 即 URL ?tab= 值；缺省 recommend 时 URL 不带 tab） */
export const HOME_TABS = [
  { key: 'recommend', label: '推荐' },
  { key: 'cos', label: 'cos' },
  { key: 'hot', label: '排行榜' },
] as const
export type HomeTabKey = (typeof HOME_TABS)[number]['key']

/** 首页排行榜周期胶囊（key 即 URL ?period= 值 + GET /rankings query.period） */
export const RANK_PERIODS = [
  { key: 'day', label: '日榜' },
  { key: 'month', label: '月榜' },
  { key: 'week', label: '周榜' },
  { key: 'year', label: '年榜' },
] as const
export type HomeRankPeriod = (typeof RANK_PERIODS)[number]['key']

const RANK_PERIOD_KEYS: readonly string[] = RANK_PERIODS.map((p) => p.key)

/** URL period 参数 → 合法周期（非法/缺省回退日榜——原型每次进排行榜重置日榜） */
export function parseRankPeriod(v: string | null): HomeRankPeriod {
  return v !== null && RANK_PERIOD_KEYS.includes(v) ? (v as HomeRankPeriod) : 'day'
}
