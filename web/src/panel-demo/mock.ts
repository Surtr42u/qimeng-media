/**
 * panel-demo 原型专用 mock 数据（全部假数据，不发任何真实请求）。
 *
 * 约定：页面与组件禁止内联业务数据，全部从这里取；
 * 波动/生成逻辑也收敛在本文件（纯函数），页面只负责渲染与定时调度。
 */

// ---------- 通用 ----------

/** 指标卡（value 为数字，展示侧做数字滚动 + 千分位格式化） */
export interface MetricCard {
  label: string
  /** 主数值（滚动动画的目标值） */
  value: number
  /** 小数位数（0 = 整数千分位，1 = 保留一位） */
  decimals?: number
  /** 主数值后的单位/辅助文本（如 "MB/s"、"天 11 时"） */
  suffix?: string
  /** 变化趋势文案（如 ↑2.1%） */
  delta: string
  deltaType: 'up' | 'down' | 'flat'
  /** 卡片底部小字补充参数 */
  sub?: string
}

// ---------- 性能页 ----------

/** 圆环指标（CPU / 内存 / 磁盘共用） */
export interface GaugeInfo {
  /** 圆环下方标注名 */
  label: string
  /** 占比 0-100（ProgressCircle 的 value） */
  usage: number
  /** 卡片内补充参数行（每条一行小字） */
  details: string[]
}

/** 性能页一次完整快照（2s 定时波动） */
export interface PerfSnapshot {
  cpu: GaugeInfo
  memory: GaugeInfo
  disks: GaugeInfo[]
  netDownMBs: number
  netUpMBs: number
  sessionDownGB: number
  sessionUpGB: number
  storageUsedTB: number
  storageTotalTB: number
  uptimeDays: number
  uptimeHours: number
}

/** 性能页 4 张指标卡的静态部分（label 与趋势；value 每次快照实时算） */
export const MOCK_METRIC_LABELS = {
  netDown: '网络下行',
  netUp: '网络上行',
  storage: '存储总占用',
  uptime: '运行时间',
} as const

/** 指标卡趋势（原型静态值） */
export const MOCK_METRIC_TRENDS: Array<Pick<MetricCard, 'delta' | 'deltaType'>> = [
  { delta: '↑ 2.1%', deltaType: 'up' },
  { delta: '↓ 0.8%', deltaType: 'down' },
  { delta: '↑ 0.4%', deltaType: 'up' },
  { delta: '持续运行', deltaType: 'flat' },
]

/** 网络峰值（本会话历史最高，静态 mock） */
export const MOCK_NET_PEAK = { downMBs: 48.2, upMBs: 12.6 } as const

/** 存储 / 运行时间指标卡的静态小字 */
export const MOCK_METRIC_SUBS = {
  storage: '媒体库 10.4 / 16 TB · 快照 1,842 个',
  uptime: '上次重启 7/14 03:12 · 负载均值 0.42',
} as const

/** 性能页初始快照 */
export const MOCK_PERF_INITIAL: PerfSnapshot = {
  cpu: {
    label: 'CPU',
    usage: 62,
    details: ['主频 3.8GHz · 20 核 28 线程', '温度 62°C · 负载进程 214 个'],
  },
  memory: {
    label: '内存',
    usage: 65,
    details: ['已用 41.7 / 64 GB', '已缓存 18.2 GB · 交换 1.1 / 8 GB'],
  },
  disks: [
    {
      label: '系统盘',
      usage: 34,
      details: ['已用 161.8 / 476 GB', '读 ↓86 MB/s · 写 ↑24 MB/s'],
    },
    {
      label: '媒体库',
      usage: 71,
      details: ['已用 10.4 / 16 TB', '读 ↓45 MB/s · 写 ↑12 MB/s'],
    },
  ],
  netDownMBs: 12.6,
  netUpMBs: 3.8,
  sessionDownGB: 3.42,
  sessionUpGB: 0.87,
  storageUsedTB: 10.6,
  storageTotalTB: 16.5,
  uptimeDays: 46,
  uptimeHours: 11,
}

const clamp = (v: number, min: number, max: number) => Math.min(max, Math.max(min, v))
const round1 = (v: number) => Math.round(v * 10) / 10
const wave = (base: number, amp: number) => base + (Math.random() - 0.5) * 2 * amp

/** 性能页刷新间隔（ms） */
export const SAMPLE_INTERVAL_MS = 2_000

/** 快照波动：CPU/内存/网速小幅抖动，磁盘与运行时间不动 */
export function jitterPerf(prev: PerfSnapshot): PerfSnapshot {
  const netDownMBs = round1(clamp(wave(prev.netDownMBs, 2.5), 0.2, 28))
  const netUpMBs = round1(clamp(wave(prev.netUpMBs, 1.2), 0.1, 12))
  // 2s 内会话累计增量 = 速率(MB/s) * 2s / 1024
  return {
    ...prev,
    cpu: {
      ...prev.cpu,
      usage: Math.round(clamp(wave(prev.cpu.usage, 5), 5, 95)),
      details: [
        prev.cpu.details[0],
        // 温度跟随负载小幅漂移，负载进程数微调，制造"活"的观感
        prev.cpu.details[1].replace(/温度 \d+°C/, `温度 ${Math.round(clamp(wave(62, 4), 45, 85))}°C`)
          .replace(/负载进程 \d+ 个/, `负载进程 ${Math.round(clamp(wave(214, 18), 120, 320))} 个`),
      ],
    },
    memory: {
      ...prev.memory,
      usage: Math.round(clamp(wave(prev.memory.usage, 2), 10, 95)),
      details: [`${round1((prev.memory.usage / 100) * 64).toFixed(1)} / 64 GB`, prev.memory.details[1]],
    },
    netDownMBs,
    netUpMBs,
    sessionDownGB: round1(prev.sessionDownGB + (netDownMBs * 2) / 1024),
    sessionUpGB: round1(prev.sessionUpGB + (netUpMBs * 2) / 1024),
  }
}

// ---------- 性能页：网络负载序列（AreaChart 上下行双系列） ----------

export interface NetSample {
  /** 采样时刻 HH:MM:SS */
  t: string
  /** 下行 MB/s（统计浏览使用的流量） */
  down: number
  /** 上行 MB/s */
  up: number
}

/** 基准速率（与初始快照一致，曲线围绕它均值回复波动，比纯随机更像真实流量） */
const NET_BASE = { down: 12.6, up: 3.8 } as const

/** 网络图窗口：60 点 × 2s 采样 = 最近 2 分钟 */
export const NET_WINDOW = 60

/** 图表系列配置（index/categories 用中文 key，图例直接显示「下行 / 上行」） */
export const NET_SERIES_META = {
  index: 't',
  categories: ['下行', '上行'],
  colors: ['blue', 'emerald'],
} as const

const fmtHMS = (d: Date) => {
  const p2 = (n: number) => String(n).padStart(2, '0')
  return `${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}`
}

/** 单点推演：随机波动 + 10% 均值回复（向基准速率回归） */
export function nextNetSample(prev: NetSample, now = new Date()): NetSample {
  const down = round1(clamp(prev.down + (Math.random() - 0.5) * 5 + (NET_BASE.down - prev.down) * 0.1, 0.2, 28))
  const up = round1(clamp(prev.up + (Math.random() - 0.5) * 2.4 + (NET_BASE.up - prev.up) * 0.1, 0.1, 12))
  return { t: fmtHMS(now), down, up }
}

/** 初始窗口：从 now-(N-1)×2s 起逐步推演，首屏即有完整曲线 */
export function initNetSeries(now = new Date()): NetSample[] {
  const start = now.getTime() - (NET_WINDOW - 1) * SAMPLE_INTERVAL_MS
  const series: NetSample[] = [{ t: fmtHMS(new Date(start)), down: NET_BASE.down, up: NET_BASE.up }]
  for (let i = 1; i < NET_WINDOW; i++) {
    series.push(nextNetSample(series[i - 1], new Date(start + i * SAMPLE_INTERVAL_MS)))
  }
  return series
}

/** NetSample → 图表行（key 映射中文） */
export function mapNetPoints(points: NetSample[]): Array<Record<string, number | string>> {
  return points.map((p) => ({ t: p.t, 下行: p.down, 上行: p.up }))
}

// ---------- 数据页：时间范围 ----------

/** 数据页统计时间范围（七天 / 三十天 / 全部） */
export type TimeRange = '7d' | '30d' | 'all'

export const TIME_RANGE_TABS: Array<{ key: TimeRange; label: string }> = [
  { key: '7d', label: '七天' },
  { key: '30d', label: '三十天' },
  { key: 'all', label: '全部' },
]

/** 日期格式化 m/d（范围文案用） */
const fmtMD = (d: Date) => `${d.getMonth() + 1}/${d.getDate()}`

/** 当前范围文案（按今天 mock 计算，如「最近 7 天 · 8/23 – 8/29」） */
function buildRangeText(range: TimeRange): string {
  const now = new Date()
  if (range === 'all') {
    // 全部时间：起点固定为库建成（mock 定为 12 个月前）
    const start = new Date(now.getFullYear(), now.getMonth() - 11, 1)
    return `全部时间 · ${start.getFullYear()}/${start.getMonth() + 1} 至今`
  }
  const days = range === '7d' ? 7 : 30
  const from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days + 1)
  return `最近 ${days} 天 · ${fmtMD(from)} – ${fmtMD(now)}`
}

// ---------- 数据页：确定性伪随机（同一范围每次刷新数据一致） ----------

/** 字符串哈希 → 种子 */
function hashStr(str: string): number {
  let h = 0
  for (let i = 0; i < str.length; i++) {
    h = (h * 31 + str.charCodeAt(i)) | 0
  }
  return Math.abs(h) || 1
}

/** 线性同余伪随机数发生器（0-1，确定性） */
function seeded(seed: number): () => number {
  let s = seed % 2147483647
  if (s <= 0) s += 2147483646
  return () => {
    s = (s * 16807) % 2147483647
    return (s - 1) / 2147483646
  }
}

// ---------- 数据页：指标卡（每个范围一组） ----------

type MetricSeed = Pick<MetricCard, 'label' | 'value' | 'decimals' | 'suffix' | 'delta' | 'deltaType'>

/** 各范围 6 张指标卡种子（后两张为互动数据：点赞 / 收藏） */
const METRICS_BY_RANGE: Record<TimeRange, MetricSeed[]> = {
  '7d': [
    { label: '总浏览', value: 31562, delta: '↑ 4.6%', deltaType: 'up' },
    { label: '总播放', value: 9204, delta: '↑ 2.3%', deltaType: 'up' },
    { label: '总停留时长', value: 2186, suffix: '小时', delta: '↑ 1.1%', deltaType: 'up' },
    { label: '今日新增浏览', value: 1286, delta: '↓ 0.6%', deltaType: 'down' },
    { label: '总点赞', value: 5124, delta: '↑ 6.8%', deltaType: 'up' },
    { label: '总收藏', value: 3810, delta: '↑ 3.2%', deltaType: 'up' },
  ],
  '30d': [
    { label: '总浏览', value: 128462, delta: '↑ 4.6%', deltaType: 'up' },
    { label: '总播放', value: 36917, delta: '↑ 2.3%', deltaType: 'up' },
    { label: '总停留时长', value: 8942, suffix: '小时', delta: '↑ 1.1%', deltaType: 'up' },
    { label: '今日新增浏览', value: 1286, delta: '↓ 0.6%', deltaType: 'down' },
    { label: '总点赞', value: 20483, delta: '↑ 6.8%', deltaType: 'up' },
    { label: '总收藏', value: 15260, delta: '↑ 3.2%', deltaType: 'up' },
  ],
  all: [
    { label: '总浏览', value: 486218, delta: '↑ 4.6%', deltaType: 'up' },
    { label: '总播放', value: 142075, delta: '↑ 2.3%', deltaType: 'up' },
    { label: '总停留时长', value: 33480, suffix: '小时', delta: '↑ 1.1%', deltaType: 'up' },
    { label: '今日新增浏览', value: 1286, delta: '↓ 0.6%', deltaType: 'down' },
    { label: '总点赞', value: 78425, delta: '↑ 6.8%', deltaType: 'up' },
    { label: '总收藏', value: 59912, delta: '↑ 3.2%', deltaType: 'up' },
  ],
}

// ---------- 数据页：趋势图 ----------

export interface TrendPoint {
  date: string
  views: number
  plays: number
}

/** 趋势图系列配置（categories 用中文名，tremor 图例直接显示 key） */
export const TREND_SERIES = {
  index: 'date',
  categories: ['浏览', '播放'],
  colors: ['blue', 'amber'],
} as const

/** 按范围生成趋势（确定性波形；all 用 12 个月粒度） */
export function generateTrend(range: TimeRange): TrendPoint[] {
  const now = new Date()
  const points: TrendPoint[] = []
  if (range === 'all') {
    // 全部：近 12 个月，月粒度
    const total = 12
    for (let i = total - 1; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
      const phase = (total - 1 - i) / total
      const views = Math.round(36000 + Math.sin(phase * Math.PI * 3) * 9000 + phase * 14000)
      const plays = Math.round(10500 + Math.sin(phase * Math.PI * 3 + 0.8) * 3600 + phase * 4200)
      points.push({ date: `${String(d.getFullYear()).slice(2)}/${d.getMonth() + 1}`, views, plays })
    }
    return points
  }
  const days = range === '7d' ? 7 : 30
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i)
    const phase = (days - 1 - i) / days
    // 周末高峰：周末乘 1.25
    const weekendBoost = [0, 6].includes(d.getDay()) ? 1.25 : 1
    const scale = days === 7 ? 4.5 : 1
    const views = Math.round((520 + Math.sin(phase * Math.PI * 3) * 140 + phase * 180) * weekendBoost * scale)
    const plays = Math.round((150 + Math.sin(phase * Math.PI * 3 + 0.8) * 55 + phase * 60) * weekendBoost * scale)
    points.push({ date: fmtMD(d), views, plays })
  }
  return points
}

/** TrendPoint → 图表数据（key 映射为中文，让 tremor 图例直接显示中文） */
export function mapTrendPoints(points: TrendPoint[]): Array<Record<string, number | string>> {
  return points.map((p) => ({ date: p.date, 浏览: p.views, 播放: p.plays }))
}

// ---------- 数据页：内容分布双环 ----------

export const CONTENT_DONUT = {
  category: 'name',
  colors: ['blue', 'violet', 'amber'],
} as const

export interface DonutSlice {
  name: string
  value: number
}

/** 内容类型分布（资产数量占比）——存量指标，不随时间范围变化 */
export const MOCK_CONTENT_BY_COUNT: DonutSlice[] = [
  { name: '图片', value: 8204 },
  { name: '视频', value: 3691 },
  { name: '动图', value: 1477 },
]

/** 浏览量占比（按类型的浏览次数）——流量指标，随时间范围变化 */
const CONTENT_VIEWS_BY_RANGE: Record<TimeRange, DonutSlice[]> = {
  '7d': [
    { name: '视频', value: 18420 },
    { name: '图片', value: 9682 },
    { name: '动图', value: 3460 },
  ],
  '30d': [
    { name: '视频', value: 74380 },
    { name: '图片', value: 39864 },
    { name: '动图', value: 14218 },
  ],
  all: [
    { name: '视频', value: 281054 },
    { name: '图片', value: 150432 },
    { name: '动图', value: 54732 },
  ],
}

// ---------- 数据页：排行榜（总览小卡 + 榜单详情页共用同一数据源） ----------

/**
 * 榜单类型（key 同时是详情页路由参数 /data/rank/:type）：
 * content=热门内容 / tags=标签 / authors=作者
 */
export type RankKind = 'content' | 'tags' | 'authors'

export interface RankEntry {
  name: string
  /** 浏览量（排序维度之一，content 榜主指标） */
  views: number
  /** 关联文件数 / 作品数（tags、authors 榜主指标） */
  count: number
  /** 收藏数（排序维度之一） */
  saves: number
  /** 累计浏览时长（分钟，排序维度之一） */
  durationMin: number
  /** 环比变化文案 */
  delta: string
  deltaType: 'up' | 'down' | 'flat'
}

export interface RankBoard {
  /** 主指标名（总览小卡行说明用） */
  metricLabel: string
  /** 全量条目（已按主指标降序；详情页按排序维度本地重排） */
  items: RankEntry[]
}

/** 榜单标题（总览卡头 + 详情页页标题 + 顶栏共用一份文案，避免三处漂移） */
export const RANK_PAGE_TITLES: Record<RankKind, string> = {
  content: '热门内容榜',
  tags: '标签排行',
  authors: '作者排行',
}

/** 总览页小卡固定展示顺序 */
export const RANK_KINDS: RankKind[] = ['content', 'tags', 'authors']

/** 路由参数收窄（无效 type 由详情页渲染兜底提示） */
export function isRankKind(v: string | undefined): v is RankKind {
  return v === 'content' || v === 'tags' || v === 'authors'
}

/** 各榜单主指标：content 按浏览量，tags / authors 按数量 */
const BOARD_MAIN_KEY: Record<RankKind, 'views' | 'count'> = {
  content: 'views',
  tags: 'count',
  authors: 'count',
}

/** 条目主指标数值（总览小卡显示用） */
export function entryMainValue(entry: RankEntry, kind: RankKind): number {
  return BOARD_MAIN_KEY[kind] === 'views' ? entry.views : entry.count
}

// 详情页排序维度（工具栏切换重排）
export type RankSortKey = 'views' | 'saves' | 'duration'

export const RANK_SORT_TABS: Array<{ key: RankSortKey; label: string }> = [
  { key: 'views', label: '按浏览' },
  { key: 'saves', label: '按收藏' },
  { key: 'duration', label: '按时长' },
]

/** 排序维度数值 */
export function entrySortValue(entry: RankEntry, key: RankSortKey): number {
  if (key === 'views') return entry.views
  if (key === 'saves') return entry.saves
  return entry.durationMin
}

/** 排序维度值展示（时长转「x 小时」，其余千分位） */
export function fmtSortValue(value: number, key: RankSortKey): string {
  if (key === 'duration') return `${(value / 60).toFixed(1)} 小时`
  return value.toLocaleString('zh-CN')
}

/** 候选池（各 50 条：详情页 Top 50 全量入榜，seeded 保证同一 kind+range 数据稳定） */
const HOT_POOL = [
  '霓虹之夜 4K 修复版', '山海旅人 · 幕后纪实', '城市微光摄影集', '星轨延时合集 Vol.2',
  '旧巷食堂（短片）', '雪线之上 · 登山日志', '夜航西飞（纪录片）', '京都秋日 · 相册',
  '长焦观鸟 2026', '胶片冲扫室 Vol.7', '环岛骑行 Vlog', '海街灯光考',
  '雨季天台手记', '深夜便利店的猫', '荒原公路 72 小时', '老宅改造实录 Vol.3',
  '云海之上 · 无人机航拍', '巷口面馆十二时辰', '极简工作台搭建指南', '城市天际线 2026 日历',
  '湿地候鸟观测记', '地铁末班车肖像', '川西秘境徒步 Vlog', '屋顶花园四季',
  '手冲咖啡入门 Vol.5', '海岛浮潜日记', '废墟探索 · 无人医院', '微距世界：昆虫记',
  '雪国列车窗景 8 小时', '老城钟表店的一天', '峡谷星野摄影教程', '街头美食地图 · 粤式篇',
  '古籍修复室纪实', '风筝海岸（短片）', '山谷木屋建造记 Vol.2', '雨林夜行记录',
  '城市桥洞音乐现场', '银河延时 · 高原篇', '猫咪咖啡馆监控剪辑', '老式胶片相机测评',
  '徽州古村漫步', '极光追逐者日志', '面包窑与深夜炉火', '城市地下空间档案',
  '樱花前线追樱记', '海上灯塔守护者', '露天电影放映夜', '芦苇荡日出延时',
  '修表匠人的工作台', '盛夏泳池假日合集',
]
const TAG_POOL = [
  '4K 修复', '延时摄影', '纪录片', '城市夜景', '人像写真', '胶片质感',
  '旅拍 Vlog', '美食探店', '自然生态', '黑白影调', '建筑空间', '二次元',
  '航拍视角', '街头抓拍', '微距世界', '古建筑', '星空银河', '雪景',
  '海岸线', '手作器物', '咖啡日常', '山地徒步', '温泉旅记', '复古汽车',
  '骑行日志', '露营装备', '街头音乐', '舞蹈现场', '舞台纪实', '宠物日常',
  '多肉植物', '雨景氛围', '雾中风景', '逆光剪影', '长曝光', '竖屏短片',
  '无人机航拍', '城市漫游', '老街小巷', '图书馆随拍', '市集烟火', '废墟探索',
  '极简主义', '日系清新', '电影感调色', '慢生活', '独居手记', '天文观测',
  '植物图鉴', '夜市纪行',
]
const AUTHOR_POOL = [
  '苏晚晴', '陆知远', '顾北辰', '林小满', '沈昭', '温叙',
  '迟野', '简白', '檀玖', '岑聿', '青梧', '祝眠',
  '江枕月', '段星野', '洛一禾', '闻人靖', '叶栖迟', '裴照',
  '顾拾光', '温故', '黎不悔', '商陆', '纪云舒', '应长风',
  '乔见山', '阮清越', '谢无恙', '孟拾叁', '唐几时', '韩江雪',
  '方栖鹭', '卫来', '程既白', '苏不合', '楚天阔', '蒋星回',
  '龚夜灯', '白霜降', '石开', '尹朝暮', '秦不语', '罗浮生',
  '夏蝉鸣', '傅山行', '曹眠', '严冬', '齐光', '唐念北',
  '许听澜', '阿澈',
]

/** 各榜单四维数值基准（浏览 / 数量 / 收藏 / 时长分钟），乘范围系数与衰减扰动生成 */
const BOARD_META: Record<
  RankKind,
  { bases: { views: number; count: number; saves: number; duration: number }; metricLabel: string }
> = {
  content: { bases: { views: 4820, count: 36, saves: 620, duration: 420 }, metricLabel: '浏览量' },
  tags: { bases: { views: 5400, count: 260, saves: 380, duration: 360 }, metricLabel: '关联文件数' },
  authors: { bases: { views: 7200, count: 8, saves: 510, duration: 480 }, metricLabel: '作品数' },
}

/**
 * 从池派生全量榜单（50 条全入榜，按主指标降序）。
 * 四个维度各自独立扰动——保证详情页切换排序键能看到真实重排，而非同序换标签。
 */
function deriveBoard(kind: RankKind, range: TimeRange): RankBoard {
  const { bases, metricLabel } = BOARD_META[kind]
  const scale = range === '7d' ? 0.25 : range === 'all' ? 3.8 : 1
  const rand = seeded(hashStr(kind + range))
  const pool = kind === 'content' ? HOT_POOL : kind === 'tags' ? TAG_POOL : AUTHOR_POOL
  // 指数衰减让 50 名内主值平滑递减（线性衰减到尾部会变负）
  const decay = (i: number) => Math.pow(0.955, i)
  const jitter = () => 0.82 + rand() * 0.36
  const gen = (base: number, i: number) => Math.max(1, Math.round(base * scale * decay(i) * jitter()))
  const items: RankEntry[] = pool.map((name, i) => {
    const views = gen(bases.views, i)
    const count = gen(bases.count, i)
    const saves = gen(bases.saves, i)
    const durationMin = gen(bases.duration, i)
    const up = rand() > 0.35
    const pct = round1(1 + rand() * 14)
    return {
      name,
      views,
      count,
      saves,
      durationMin,
      delta: `${up ? '↑' : '↓'} ${pct.toFixed(1)}%`,
      deltaType: (up ? 'up' : 'down') as RankEntry['deltaType'],
    }
  })
  items.sort((a, b) => entryMainValue(b, kind) - entryMainValue(a, kind))
  return { metricLabel, items }
}

/** 榜单详情页统计概览卡（value=数字滚动；text=文本值如「最热标签」） */
export interface RankOverviewStat {
  label: string
  value?: number
  text?: string
  /** 数值后缀（如「天」） */
  suffix?: string
  sub?: string
}

/** 三个榜单的概览统计（原型静态值，口径对齐数据页「全部」范围） */
export const RANK_OVERVIEWS: Record<RankKind, RankOverviewStat[]> = {
  content: [
    { label: '内容总数', value: 13372, sub: '图片 8204 · 视频 3691 · 动图 1477' },
    { label: '总浏览', value: 486218, sub: '近 30 天 ↑ 4.6%' },
    { label: '收录时长', value: 366, suffix: '天', sub: '2025/08/28 建库至今' },
  ],
  tags: [
    { label: '标签总数', value: 50, sub: '自动打标 42 · 手动 8' },
    { label: '最热标签', text: '4K 修复', sub: '关联文件 2,846 个' },
    { label: '本月新增', value: 6, sub: '待人工复核 1 个' },
  ],
  authors: [
    { label: '作者总数', value: 50, sub: '本月活跃 31 位' },
    { label: '作品总数', value: 6234, sub: '含合集拆分条目' },
    { label: '含 COS 作者', value: 12, sub: '占作者总数 24%' },
  ],
}

// ---------- 数据页：单范围汇总 ----------

export interface DataRangeData {
  /** 范围文案（如「最近 7 天 · 8/23 – 8/29」） */
  rangeText: string
  metrics: MetricCard[]
  trend: TrendPoint[]
  contentByViews: DonutSlice[]
  boards: Record<RankKind, RankBoard>
}

/** 每个时间范围一组完整数据（页面切换范围时整体替换） */
export const DATA_BY_RANGE: Record<TimeRange, DataRangeData> = {
  '7d': { rangeText: buildRangeText('7d'), ...makeRangeData('7d') },
  '30d': { rangeText: buildRangeText('30d'), ...makeRangeData('30d') },
  all: { rangeText: buildRangeText('all'), ...makeRangeData('all') },
}

function makeRangeData(range: TimeRange): Omit<DataRangeData, 'rangeText'> {
  return {
    metrics: METRICS_BY_RANGE[range],
    trend: generateTrend(range),
    contentByViews: CONTENT_VIEWS_BY_RANGE[range],
    boards: {
      content: deriveBoard('content', range),
      tags: deriveBoard('tags', range),
      authors: deriveBoard('authors', range),
    },
  }
}

// ---------- 内容浏览页：条目文件列表（胶囊筛选 / 排序 / 预览） ----------

export type MediaType = '视频' | '图片' | '动图' | '音频'

export interface DetailFile {
  name: string
  mediaType: MediaType
  /** 展示用大小文案（如 "1.2 GB"） */
  size: string
  /** 原始大小 MB（条目概览聚合用） */
  sizeMB: number
  /** 收录时间 YYYY-MM-DD HH:mm（字典序即时间序，「最新」排序直接比较字符串） */
  time: string
  views: number
  /** 时长分钟（图片为 0；「最长」排序用） */
  durationMin: number
  /** 出场角色（旧项目药丸的「角色」维度；可为空数组=无角色数据） */
  characters: string[]
  /** 归属作品（旧项目药丸的「作品」维度，COS 专有语义；无 = 不归属任何作品） */
  work?: string
  /** 分区（旧项目第一维药丸：常规 / COS） */
  partition: '常规' | 'COS'
}

/**
 * 条目 → 文件列表（mock 任意数量，浏览页网格默认 24 个）。
 * 类型池默认不含音频——浏览页胶囊筛选组只有 视频/图片/动图 三档。
 * seed 与 entryName 分离：调用方可用「kind:name」这类带前缀种子隔离同名条目，
 * 而文件显示名保持纯净（不带 kind 前缀）。
 */
export function generateDetailFiles(
  entryName: string,
  count = 24,
  typePool: MediaType[] = ['视频', '图片', '动图'],
  seed = entryName,
): DetailFile[] {
  const rand = seeded(hashStr(seed))
  const files: DetailFile[] = []
  const now = new Date()
  for (let i = 0; i < count; i++) {
    const mediaType = typePool[Math.floor(rand() * typePool.length)]
    const sizeMB = Math.round(40 + rand() * 3800)
    const size = sizeMB >= 1024 ? `${round1(sizeMB / 1024)} GB` : `${sizeMB} MB`
    const daysAgo = Math.floor(rand() * 60)
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - daysAgo, 8 + Math.floor(rand() * 14), Math.floor(rand() * 60))
    const p2 = (n: number) => String(n).padStart(2, '0')
    // 后缀按类型更像真实文件：图片 IMG_xxxx / 动图 GIF_xxxx / 视频 片段 NN
    const suffix =
      mediaType === '图片'
        ? `IMG_${1000 + Math.floor(rand() * 9000)}`
        : mediaType === '动图'
          ? `GIF_${100 + Math.floor(rand() * 900)}`
          : `片段 ${p2(i + 1)}`
    // 角色维度：0~2 个（约两成文件无角色，保证「角色组」聚合各条目有差异）
    const characterCount = Math.floor(rand() * 3)
    const characters: string[] = []
    for (let c = 0; c < characterCount; c++) {
      const role = MOCK_CHARACTERS[Math.floor(rand() * MOCK_CHARACTERS.length)]
      if (!characters.includes(role)) characters.push(role)
    }
    // 作品维度：约四成文件归属某作品（作品是 COS 专有语义，不是全覆盖）
    const work = rand() < 0.4 ? MOCK_WORKS[Math.floor(rand() * MOCK_WORKS.length)] : undefined
    // 分区维度：约 55% 归 COS（旧版 COS 体量通常更大；COS 文件更可能有作品归属）
    const isCos = rand() < 0.55
    const partition: DetailFile['partition'] = isCos ? 'COS' : '常规'
    const finalWork = work ?? (isCos && rand() < 0.5 ? MOCK_WORKS[Math.floor(rand() * MOCK_WORKS.length)] : undefined)
    files.push({
      name: `${entryName} · ${suffix}`,
      mediaType,
      size,
      sizeMB,
      time: `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`,
      views: Math.round(20 + rand() * 2400),
      // 图片没有时长概念记 0，「最长」排序自然沉底；音视频 1-59 分钟
      durationMin: mediaType === '图片' ? 0 : 1 + Math.floor(rand() * 59),
      characters,
      work: finalWork,
      partition,
    })
  }
  return files
}

/** 条目头概览（由文件列表聚合，mock 层算好页面只渲染） */
export interface BrowseSummary {
  fileCount: number
  totalSize: string
  totalViews: number
}

/** 汇总文件列表 → 条目概览字段 */
export function summarizeFiles(files: DetailFile[]): BrowseSummary {
  const totalMB = files.reduce((sum, f) => sum + f.sizeMB, 0)
  return {
    fileCount: files.length,
    totalSize: totalMB >= 1024 ? `${round1(totalMB / 1024)} GB` : `${totalMB} MB`,
    totalViews: files.reduce((sum, f) => sum + f.views, 0),
  }
}

/** 内容浏览页条目类型（路由 /data/browse/:kind/:id 的 kind） */
export type BrowseKind = 'content' | 'tag' | 'author'

/** kind → 头部 Badge 文案 / 返回链接文案与目标（返回目标 = 对应榜单详情页路由 type） */
export const BROWSE_KIND_META: Record<
  BrowseKind,
  { badge: string; backLabel: string; rankType: RankKind }
> = {
  content: { badge: '内容', backLabel: '热门内容榜', rankType: 'content' },
  tag: { badge: '标签', backLabel: '标签排行', rankType: 'tags' },
  author: { badge: '作者', backLabel: '作者排行', rankType: 'authors' },
}

/** 榜单类型 → 内容浏览页 kind（行点击导航映射） */
export const BROWSE_KIND_BY_RANK: Record<RankKind, BrowseKind> = {
  content: 'content',
  tags: 'tag',
  authors: 'author',
}

/** 路由参数收窄（无效 kind 由页面渲染兜底提示） */
export function isBrowseKind(v: string | undefined): v is BrowseKind {
  return v === 'content' || v === 'tag' || v === 'author'
}

// 浏览页胶囊筛选（旧版两段式：第一段=维度选择行「分区/作品/角色/类型」，
// 第二段=所选维度的值胶囊（带计数，按计数降序）；各维度 AND 组合过滤）

/** 维度（两段式第一段的可选项） */
export type BrowseDimension = 'partition' | 'work' | 'role' | 'type'

/** 维度行顺序（旧版惯例：类型属于文件属性维度，渲染层在其前加竖线分隔） */
export const DIMENSION_META: { key: BrowseDimension; label: string }[] = [
  { key: 'partition', label: '分区' },
  { key: 'work', label: '作品' },
  { key: 'role', label: '角色' },
  { key: 'type', label: '类型' },
]

/** 类型维度的固定值序（其余维度按计数动态排序） */
export type BrowseTypeFilter = '全部' | '视频' | '图片' | '动图'
export const BROWSE_TYPE_OPTIONS: BrowseTypeFilter[] = ['全部', '视频', '图片', '动图']

/** 排序组（单选） */
export type BrowseSortKey = '最新' | '最热' | '最长'
export const BROWSE_SORT_OPTIONS: BrowseSortKey[] = ['最新', '最热', '最长']

/** 角色池（旧项目「角色」药丸维度的 mock 来源；seeded 抽取保证同条目刷新一致） */
export const MOCK_CHARACTERS = ['雪莉', '诺瓦·林', '星野绘真', '顾清岚', '薇拉', '栖月', '远坂澪', '洛天依'] as const

/** 作品池（旧项目「作品」药丸维度——COS 专有语义） */
export const MOCK_WORKS = ['绮梦物语 vol.1', '星海远征', '夜城回响', '白昼幻影', '终焉之诗'] as const

/** 四个维度当前的选中值（「全部」= 不过滤该维度） */
export interface BrowseFilters {
  partition: string
  work: string
  role: string
  type: string
}

export const BROWSE_FILTERS_DEFAULT: BrowseFilters = {
  partition: '全部',
  work: '全部',
  role: '全部',
  type: '全部',
}

/** 单个维度的值与计数（「全部」= 该维度其余过滤条件下的集合总数） */
export interface DimensionValueCount {
  value: string
  count: number
}

function dimensionMatches(f: DetailFile, dim: BrowseDimension, selected: BrowseFilters): boolean {
  switch (dim) {
    case 'partition':
      return selected.partition === '全部' || f.partition === selected.partition
    case 'work':
      return selected.work === '全部' || f.work === selected.work
    case 'role':
      return selected.role === '全部' || f.characters.includes(selected.role)
    case 'type':
      return selected.type === '全部' || f.mediaType === selected.type
  }
}

/**
 * 维度值计数（两段式第二段的数据源）。
 * 级联口径：每个值的计数基于「其他三维已选过滤后」的剩余集合——
 * 选了某作品后，角色维度的计数只统计该作品下的文件。
 */
export function countDimension(
  files: DetailFile[],
  dim: BrowseDimension,
  selected: BrowseFilters,
): DimensionValueCount[] {
  const counts = new Map<string, number>()
  let total = 0
  for (const f of files) {
    // 本维度自身排除在外（计的是"其他条件确定后，本维度各值还有多少"）
    let pass = true
    for (const { key } of DIMENSION_META) {
      if (key !== dim && !dimensionMatches(f, key, selected)) {
        pass = false
        break
      }
    }
    if (!pass) continue
    total++
    const vals: string[] =
      dim === 'type'
        ? [f.mediaType]
        : dim === 'partition'
          ? [f.partition]
          : dim === 'role'
            ? f.characters
            : f.work
              ? [f.work]
              : []
    for (const v of vals) counts.set(v, (counts.get(v) ?? 0) + 1)
  }
  const valueCounts = [...counts.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) => b.count - a.count)
  return [{ value: '全部', count: total }, ...valueCounts]
}

/** 维度聚合结果（条目头概览计数用，不受筛选影响） */
export interface BrowseFacets {
  roles: string[]
  works: string[]
}

/** 聚合文件集合中出现过的角色/作品（去重、保序） */
export function aggregateBrowseFacets(files: DetailFile[]): BrowseFacets {
  const roles: string[] = []
  const works: string[] = []
  for (const f of files) {
    for (const c of f.characters) if (!roles.includes(c)) roles.push(c)
    if (f.work && !works.includes(f.work)) works.push(f.work)
  }
  return { roles, works }
}

/** 胶囊过滤（四维 AND）+ 排序（纯函数） */
export function applyBrowseFilter(
  files: DetailFile[],
  filters: BrowseFilters,
  sort: BrowseSortKey,
): DetailFile[] {
  const filtered = files.filter(
    (f) =>
      (filters.partition === '全部' || f.partition === filters.partition) &&
      (filters.work === '全部' || f.work === filters.work) &&
      (filters.role === '全部' || f.characters.includes(filters.role)) &&
      (filters.type === '全部' || f.mediaType === filters.type),
  )
  const sorted = [...filtered]
  if (sort === '最新') sorted.sort((a, b) => b.time.localeCompare(a.time))
  else if (sort === '最热') sorted.sort((a, b) => b.views - a.views)
  else sorted.sort((a, b) => b.durationMin - a.durationMin)
  return sorted
}

// ---------- 维护页 ----------

/** 回收站概况 */
export const MOCK_TRASH = {
  count: 23,
  usedGB: 48.6,
  capacityGB: 200,
}

/** 用户端日志 */
export interface LogEntry {
  time: string
  level: 'error' | 'warn' | 'info'
  source: string
  message: string
}

export const MOCK_LOGS: LogEntry[] = [
  { time: '08-29 14:52:11', level: 'error', source: 'player/web', message: '视频解码失败：不支持的编码 HEVC 10bit（Chromium 旧版）' },
  { time: '08-29 14:47:03', level: 'warn', source: 'scanner', message: '缩略图生成耗时超过 5s：/media/photos/2026-08/IMG_8871.jpg' },
  { time: '08-29 14:31:58', level: 'info', source: 'scanner', message: '增量扫描完成：新增 14 个资产，耗时 42s' },
  { time: '08-29 13:58:40', level: 'error', source: 'api-client/android', message: '上传中断：网络切换导致连接重置（已自动重试 1 次）' },
  { time: '08-29 13:22:19', level: 'warn', source: 'player/tv', message: '缓冲水位低于 2s，触发码率降档：12Mbps → 8Mbps' },
  { time: '08-29 12:40:05', level: 'info', source: 'uploader', message: '相册「京都秋日」接收上传 38 个文件，全部通过校验' },
  { time: '08-29 11:57:44', level: 'warn', source: 'scanner', message: '检测到重名资产：IMG_2026.jpg（已按规则重命名）' },
  { time: '08-29 11:03:27', level: 'error', source: 'player/web', message: '字幕加载 404：/subs/asset/4472/zh-CN.srt' },
  { time: '08-29 10:15:09', level: 'info', source: 'server', message: '定时任务：媒体库统计快照已更新' },
  { time: '08-29 09:32:51', level: 'warn', source: 'api-client/ios', message: '轮询退避触发：连续 3 次 SSE 断线重连' },
]

/** 日志级别 → Badge 视觉（error 红 / warn 黄 / info 灰） */
export const LOG_LEVEL_BADGE: Record<LogEntry['level'], { label: string; variant: 'error' | 'warning' | 'neutral' }> = {
  error: { label: '错误', variant: 'error' },
  warn: { label: '警告', variant: 'warning' },
  info: { label: '信息', variant: 'neutral' },
}

// ---------- 设置页 ----------

/** 扫描设置（原型初始值） */
export const MOCK_SCAN_SETTINGS = {
  concurrency: 4,
  thumbLongEdge: 640,
}

/** 上传设置（原型初始值） */
export const MOCK_UPLOAD_SETTINGS = {
  sizeLimitMB: 2048,
  autoAccept: true,
}

/** 数值输入范围约束（原型展示用） */
export const SETTING_LIMITS = {
  concurrency: { min: 1, max: 16, label: '扫描并发数', hint: '同时扫描的文件数，过大可能拖慢 NAS 其他服务' },
  thumbLongEdge: { min: 240, max: 4096, label: '缩略图长边', hint: '缩略图最长边的像素尺寸，越大越清晰、越占空间' },
  sizeLimitMB: { min: 64, max: 102400, label: '单文件大小上限 (MB)', hint: '超过上限的文件将被拒绝上传' },
} as const

/** 界面设置说明（跟随系统主题，不可改） */
export const APPEARANCE_NOTE =
  '主题跟随系统设置（浅色 / 深色），不提供应用内切换。修改系统外观后界面即时跟随。'

// ---------- 首页：B 站风推荐卡片流 ----------

export interface HomeFeedItem {
  /** 唯一 key（生成序号即可） */
  id: string
  /** 文件名（即卡片标题） */
  title: string
  /** UP 主 */
  author: string
  views: number
  /** 相对时间文案（如「3 天前」） */
  timeText: string
  /** 收录时间（详情 sheet 元数据用） */
  time: string
  /** 封面角标时长文案（视频 = mm:ss；图片/动图无角标） */
  durationText?: string
  size: string
  mediaType: MediaType
  /** 标签（详情 sheet Badge 行，2~3 个） */
  tags: string[]
  /** 封面渐变索引（渲染层按索引查渐变 class 列表） */
  gradient: number
}

/** 首页推荐流条数（宽屏 4 列 × 7 行的量级） */
export const HOME_FEED_COUNT = 28

/** 浏览量紧凑格式（B 站风：3.2 万） */
export function fmtViewCount(v: number): string {
  return v >= 10_000 ? `${round1(v / 10_000)} 万` : v.toLocaleString('zh-CN')
}

/**
 * 首页推荐流（seeded 确定性生成，刷新一致）。
 * 标题 = 文件名风格（条目名 + 片段/图片序号），元信息行 = UP 主 / 浏览量 / 相对时间。
 */
export function generateHomeFeed(count = HOME_FEED_COUNT): HomeFeedItem[] {
  const rand = seeded(hashStr('home-feed'))
  const now = new Date()
  const p2 = (n: number) => String(n).padStart(2, '0')
  const items: HomeFeedItem[] = []
  for (let i = 0; i < count; i++) {
    const base = HOT_POOL[Math.floor(rand() * HOT_POOL.length)]
    const isVideo = rand() < 0.7
    const mediaType: MediaType = isVideo ? '视频' : rand() < 0.6 ? '图片' : '动图'
    const durationMin = isVideo ? 1 + Math.floor(rand() * 59) : 0
    const durationSec = Math.floor(rand() * 60)
    const daysAgo = Math.floor(rand() * 30)
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - daysAgo, 8 + Math.floor(rand() * 14), Math.floor(rand() * 60))
    const sizeMB = Math.round(40 + rand() * 3800)
    // 标签 2~3 个（去重抽取）
    const tags: string[] = []
    while (tags.length < 2 + Math.floor(rand() * 2)) {
      const t = TAG_POOL[Math.floor(rand() * TAG_POOL.length)]
      if (!tags.includes(t)) tags.push(t)
    }
    items.push({
      id: `feed-${i}`,
      title: `${base} · ${isVideo ? `片段 ${p2(i + 1)}` : `IMG_${1000 + Math.floor(rand() * 9000)}`}`,
      author: AUTHOR_POOL[Math.floor(rand() * AUTHOR_POOL.length)],
      views: Math.round(800 + rand() * 420_000),
      timeText: daysAgo === 0 ? '今天' : daysAgo < 7 ? `${daysAgo} 天前` : `${Math.floor(daysAgo / 7)} 周前`,
      time: `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`,
      durationText: isVideo ? `${p2(durationMin)}:${p2(durationSec)}` : undefined,
      size: sizeMB >= 1024 ? `${round1(sizeMB / 1024)} GB` : `${sizeMB} MB`,
      mediaType,
      tags,
      gradient: i % 5,
    })
  }
  return items
}

// ---------- 相册：全量内容池 ----------

/** 相册来源条目数（三条榜单池各取前几名合并，保证分区/作品/角色/类型四维都有覆盖） */
const GALLERY_SOURCE_COUNTS = { content: 12, tags: 6, authors: 6 } as const

/** 相册每条目展开的文件数（12+6+6=24 条目 × 6 = 144 文件，全量聚合量级适中） */
const GALLERY_FILES_PER_ENTRY = 6

/**
 * 相册全量文件池：把若干榜单条目各自展开成文件列表后合并，
 * 四维 facet（分区/作品/角色/类型）在此全量上聚合；seeded 保证刷新一致。
 */
export function generateGalleryItems(): DetailFile[] {
  const sources = [
    ...HOT_POOL.slice(0, GALLERY_SOURCE_COUNTS.content),
    ...TAG_POOL.slice(0, GALLERY_SOURCE_COUNTS.tags),
    ...AUTHOR_POOL.slice(0, GALLERY_SOURCE_COUNTS.authors),
  ]
  return sources.flatMap((name) =>
    generateDetailFiles(name, GALLERY_FILES_PER_ENTRY, undefined, `gallery:${name}`),
  )
}

// ---------- 我的：头部资料 + 浏览历史 ----------

/** 「我的」头部卡资料（库数量 / 文件总数 / 总大小，mock 静态值） */
export const MOCK_MINE_PROFILE = {
  name: '绮梦',
  libraryCount: 6,
  fileCount: 13372,
  totalSize: '10.4 TB',
} as const

export interface WatchHistoryItem {
  fileName: string
  /** 观看时间文案（如「今天 14:32」） */
  watchedAt: string
  /** 观看进度 0-100（看完 100，中途退出按比例） */
  progress: number
  /** 片长文案（如 12:36） */
  durationText: string
}

export interface WatchHistoryGroup {
  label: '今天' | '昨天' | '更早'
  items: WatchHistoryItem[]
}

/** 浏览历史（mock 静态值，按今天 / 昨天 / 更早分组） */
export const MOCK_WATCH_HISTORY: WatchHistoryGroup[] = [
  {
    label: '今天',
    items: [
      { fileName: '云海之上 · 无人机航拍 · 片段 03', watchedAt: '今天 14:32', progress: 64, durationText: '08:12' },
      { fileName: '霓虹之夜 4K 修复版 · 片段 11', watchedAt: '今天 13:05', progress: 100, durationText: '21:47' },
      { fileName: '深夜便利店的猫 · 片段 02', watchedAt: '今天 10:18', progress: 31, durationText: '05:33' },
      { fileName: '城市微光摄影集 · IMG_4821', watchedAt: '今天 09:44', progress: 100, durationText: '图片' },
    ],
  },
  {
    label: '昨天',
    items: [
      { fileName: '雪线之上 · 登山日志 · 片段 06', watchedAt: '昨天 22:51', progress: 82, durationText: '16:20' },
      { fileName: '手冲咖啡入门 Vol.5 · 片段 01', watchedAt: '昨天 20:09', progress: 45, durationText: '11:05' },
      { fileName: '微距世界：昆虫记 · GIF_377', watchedAt: '昨天 18:37', progress: 100, durationText: '动图' },
      { fileName: '老宅改造实录 Vol.3 · 片段 08', watchedAt: '昨天 15:12', progress: 12, durationText: '24:59' },
    ],
  },
  {
    label: '更早',
    items: [
      { fileName: '极光追逐者日志 · 片段 04', watchedAt: '3 天前 21:26', progress: 97, durationText: '18:44' },
      { fileName: '雨季天台手记 · 片段 02', watchedAt: '4 天前 19:03', progress: 55, durationText: '09:17' },
      { fileName: '京都秋日 · 相册 · IMG_1204', watchedAt: '1 周前 16:40', progress: 100, durationText: '图片' },
      { fileName: '环岛骑行 Vlog · 片段 09', watchedAt: '2 周前 11:22', progress: 73, durationText: '27:36' },
      { fileName: '山谷木屋建造记 Vol.2 · 片段 05', watchedAt: '3 周前 20:58', progress: 100, durationText: '31:02' },
    ],
  },
]
