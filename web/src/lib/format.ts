/**
 * 展示格式化工具（卡片/详情页共用；纯函数）。
 */

import { LOCALE_ZH } from './constants'

/** 毫秒时长 → mm:ss（超一小时 h:mm:ss）；与卡片时长角标/详情信息共用 */
export function formatDuration(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** ISO 日期 → 卡片短日期「M-D」（与原型卡片 meta 口径一致） */
export function formatShortDate(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1}-${d.getDate()}`
}

/** 卡片作者行文本（原型 #4：标题下作者行）：authorNames[0]，多作者压缩
 *  为「名 等N」；无作者回退出处分区 source；两者皆无返回 undefined（不渲染）。 */
export function formatCardUp(authorNames?: string[] | null, source?: string | null): string | undefined {
  const first = authorNames?.[0]
  if (!first) return source || undefined
  const n = authorNames?.length ?? 1
  return n > 1 ? `${first} 等${n}` : first
}

/** 作者行显示名（原型 authorDisplayName）：COS 作者追加「 ·COS」标识 */
export function authorDisplayName(a: { displayName?: string | null; type?: string | null }): string {
  const name = a.displayName ?? ''
  return a.type === 'cos' ? `${name} ·COS` : name
}

/** 字节 → 人类可读大小（回收站/详情信息共用） */
export function formatBytes(n: number): string {
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(1)} GB`
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(1)} MB`
  if (n >= 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${n} B`
}

/** 目录树节点显示名（W-1 共享 DirTree 用）：路径末段；根（''）= 库根 */
export function dirLabel(path?: string | null): string {
  const seg = (path ?? '').split(/[\\/]/).filter(Boolean)
  return seg[seg.length - 1] ?? '库根'
}

/** 展示计数（排行角标/统计数字）：万以上压缩为 x.x万（截尾 .0），其余千分位分组 */
export function formatCount(n?: number | null): string {
  if (n === undefined || n === null || Number.isNaN(n)) return '0'
  if (n >= 10000) {
    const w = n / 10000
    const s = w >= 100 ? String(Math.round(w)) : w.toFixed(1).replace(/\.0$/, '')
    return `${s}万`
  }
  return n.toLocaleString(LOCALE_ZH)
}

/** Unix 毫秒时间戳 → 本地「M-D HH:mm」（历史卡观看时间；月份不补零，与 formatShortDate 同口径） */
export function formatDateTime(ts?: number | null): string {
  if (ts === undefined || ts === null || ts <= 0) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${d.getMonth() + 1}-${d.getDate()} ${hh}:${mm}`
}

/** 设备当前时区相对 UTC 的偏移分钟数（东八区=480）——协议 GET /assets 的
 *  tzOffsetMinutes 唯一取值来源（`getTimezoneOffset()` 符号相反，取负）。
 *  请求参数与客户端日界（dateLabel/localDayKey 缺省）同源，服务端只认偏移量。 */
export function deviceTzOffsetMinutes(d: Date = new Date()): number {
  return -d.getTimezoneOffset()
}

const MS_PER_DAY = 86_400_000

/** 固定偏移下的「日序号」（自 1970-01-01 起的天数，向负无穷取整）：先把时间戳
 *  平移到「本地日 = UTC 日」的坐标再按整天切分。纯整数运算、不查时区库——
 *  这是标签与分桶键恒同源的关键（理由见 dateLabel）。 */
function localEpochDay(ms: number, tzOffsetMinutes: number): number {
  return Math.floor((ms + tzOffsetMinutes * 60_000) / MS_PER_DAY)
}

function parseIsoMs(iso?: string | null): number | null {
  if (!iso) return null
  const t = Date.parse(iso)
  return Number.isNaN(t) ? null : t
}

/** ISO 日期 → 相册时间分区标签（原型 app.js dateLabel，复刻旧版 MediaBrowserLogic）：
 *  今天 / 昨天 / 2~6 天前→周X（周一~周日）/ 更早→yyyy-MM-dd；
 *  空值或非法日期返回空串（调用方不分组、不渲染组头）。
 *
 *  **固定偏移口径**（2026-10-10 审计返工）：日界一律用 [tzOffsetMinutes] 这一个
 *  偏移算，不查该日期当时的历史时区规则（夏令时区域夏冬偏移差 1 小时）。组头
 *  计数由服务端按同一个偏移分桶（browse.sql CountAssetsByLocalDay），客户端按历史
 *  规则算日键会与分桶键劈叉 → 查不到计数、回退已加载条数，「数字随滚动跳增」的
 *  原缺陷在夏令时区复现。对 Asia/Shanghai（无夏令时）行为零变化。 */
export function dateLabel(iso?: string | null, tzOffsetMinutes?: number): string {
  const t = parseIsoMs(iso)
  if (t === null) return ''
  const off = tzOffsetMinutes ?? deviceTzOffsetMinutes()
  const day = localEpochDay(t, off)
  const diff = localEpochDay(Date.now(), off) - day
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  // 平移坐标下按 UTC 取星期/年月日（不引入本地时区规则）
  const d = new Date(day * MS_PER_DAY)
  if (diff >= 2 && diff <= 6) return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getUTCDay()]
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`
}

/** 本地日期键（y-m-d 数值串）：历史「今天/昨天/更早」按天分组用 */
export function localDateKey(ts?: number | null): string {
  if (ts === undefined || ts === null || ts <= 0) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
}

/** ISO 时间 → 本地日历日键（yyyy-MM-dd，补零）：服务端 dateCounts 分桶键
 *  的客户端镜像（协议 GET /assets dateCounts，2026-10-10 加）。服务端按请求
 *  tzOffsetMinutes 把 UTC 的 mtime 折算成本地日（`date(mtime,'+480 minutes')`），
 *  浏览器必须用**同一个固定偏移**折算——两侧同键，组头精确计数才能与分组对齐
 *  （固定偏移而非历史时区规则的理由见 dateLabel）。空值/非法日期返回空串。 */
export function localDayKey(iso?: string | null, tzOffsetMinutes?: number): string {
  const t = parseIsoMs(iso)
  if (t === null) return ''
  const off = tzOffsetMinutes ?? deviceTzOffsetMinutes()
  const d = new Date(localEpochDay(t, off) * MS_PER_DAY)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`
}
