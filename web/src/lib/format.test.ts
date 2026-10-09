import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  authorDisplayName,
  dateLabel,
  dirLabel,
  formatBytes,
  formatCardUp,
  formatCount,
  formatDateTime,
  formatDuration,
  formatShortDate,
  localDateKey,
  localDayKey,
} from './format'

// 固定时钟：本地 2026-09-07（周一）12:00——日期串无时区后缀按本地时区解析，
// 断言全部用「相对今天 N 天」的本地日历日构造，不依赖运行环境时区。
// 每条用例后恢复真实时钟（自清理），避免污染同文件其他断言。
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-07T12:00:00'))
})
afterEach(() => {
  vi.useRealTimers()
})

/** 相对固定今天偏移 N 天的 ISO 串（本地日历日中午，规避跨日时区偏移） */
function isoFromToday(days: number): string {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() + days, 12).toISOString()
}

describe('dateLabel', () => {
  it('今天 / 昨天', () => {
    expect(dateLabel(isoFromToday(0))).toBe('今天')
    expect(dateLabel(isoFromToday(-1))).toBe('昨天')
  })

  it('2~6 天前 → 周X（本地 2026-09-07 周一：-3=周五、-6=周二）', () => {
    expect(dateLabel(isoFromToday(-3))).toBe('周五')
    expect(dateLabel(isoFromToday(-6))).toBe('周二')
  })

  it('更早 → yyyy-MM-dd（7 天前为分档边界）；未来日期同落此档', () => {
    expect(dateLabel(isoFromToday(-7))).toBe('2026-08-31')
    expect(dateLabel(isoFromToday(-30))).toBe('2026-08-08')
    expect(dateLabel(isoFromToday(1))).toBe('2026-09-08')
  })

  it('空值 / 非法日期 → 空串（调用方不渲染组头）', () => {
    expect(dateLabel(undefined)).toBe('')
    expect(dateLabel(null)).toBe('')
    expect(dateLabel('')).toBe('')
    expect(dateLabel('not-a-date')).toBe('')
  })

  // 固定偏移口径（2026-10-10 审计返工）：日界用请求里那一个固定偏移算，
  // 不查该日期当时的历史时区规则——否则夏令时区域会与服务端分桶键劈叉。
  it('固定偏移：同一时刻在两个偏移下落在不同本地日', () => {
    const iso = '2026-07-01T04:30:00.000Z'
    expect(localDayKey(iso, -300)).toBe('2026-06-30') // EST（请求时刻的偏移）
    expect(localDayKey(iso, -240)).toBe('2026-07-01') // EDT（该日期当时的历史规则）
    expect(localDayKey(iso, 480)).toBe('2026-07-01')
    expect(localDayKey(iso, 0)).toBe('2026-07-01')
    // 非法/空值恒空串
    expect(localDayKey('', -300)).toBe('')
    expect(localDayKey(null, -300)).toBe('')
  })

  it('固定偏移：标签与日键同源（同一天同一偏移下不会一个说今天一个算昨天）', () => {
    const iso = '2026-07-01T04:30:00.000Z'
    // 以该时刻为「现在」不可注入，故只锁日键的确定性；标签档位由 album-grouping 用例覆盖
    expect(localDayKey(iso, -300)).toBe('2026-06-30')
    expect(dateLabel('2026-06-30T12:00:00.000Z', 480)).toBe('2026-06-30')
  })
})

describe('formatBytes', () => {
  it('B 档：不足 1KB 原样数字', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
  })

  it('KB 档：整数取整、进位边界', () => {
    expect(formatBytes(1024)).toBe('1 KB')
    expect(formatBytes(1536)).toBe('2 KB') // 1.5 KB → toFixed(0) 四舍五入
    expect(formatBytes(1024 ** 2 - 1)).toBe('1024 KB') // KB 档上限边界
  })

  it('MB / GB 档：一位小数（含 .0）', () => {
    expect(formatBytes(1024 ** 2)).toBe('1.0 MB')
    expect(formatBytes(1.5 * 1024 ** 2)).toBe('1.5 MB')
    expect(formatBytes(1024 ** 3)).toBe('1.0 GB')
    expect(formatBytes(2.5 * 1024 ** 3)).toBe('2.5 GB')
  })
})

describe('formatCount', () => {
  it('空值 / NaN → 回退 "0"', () => {
    expect(formatCount(undefined)).toBe('0')
    expect(formatCount(null)).toBe('0')
    expect(formatCount(Number.NaN)).toBe('0')
  })

  it('万以下：千分位分组', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(9999)).toBe('9,999')
  })

  it('1 万~100 万：一位小数并截尾 .0', () => {
    expect(formatCount(10000)).toBe('1万')
    expect(formatCount(12345)).toBe('1.2万')
    expect(formatCount(99999)).toBe('10万') // 9.9999 → toFixed(1) = '10.0' → 截尾
  })

  it('百万级起：整数万（四舍五入）', () => {
    expect(formatCount(1000000)).toBe('100万')
    expect(formatCount(1234567)).toBe('123万')
  })
})

describe('formatDuration', () => {
  it('mm:ss 与 h:mm:ss 分档：秒/分进位、满 1h 带时位、不足 1h 不带', () => {
    expect(formatDuration(0)).toBe('0:00')
    expect(formatDuration(59_000)).toBe('0:59')
    expect(formatDuration(60_000)).toBe('1:00')
    expect(formatDuration(3_599_999)).toBe('59:59') // 差 1ms 满 1h，仍是分:秒档
    expect(formatDuration(3_600_000)).toBe('1:00:00')
    expect(formatDuration(3_661_000)).toBe('1:01:01')
  })

  it('非法输入按实际行为产出（毫秒直除逐位取余，无防御）', () => {
    expect(formatDuration(Number.NaN)).toBe('NaN:NaN')
    expect(formatDuration(-1000)).toBe('-1:-1') // 负毫秒：时/分/秒取余全为负
    // null 数值化为 0、undefined 数值化为 NaN（JS 除法强转，签名收 number 故加断言）
    expect(formatDuration(null as unknown as number)).toBe('0:00')
    expect(formatDuration(undefined as unknown as number)).toBe('NaN:NaN')
  })
})

describe('formatShortDate', () => {
  it('ISO 日期 → M-D（月/日不补零；无时区后缀按本地日历日解析）', () => {
    expect(formatShortDate('2026-09-07')).toBe('9-7')
    expect(formatShortDate('2026-01-05T08:00:00')).toBe('1-5')
  })

  it('空值 / 非法日期 → 空串', () => {
    expect(formatShortDate(undefined)).toBe('')
    expect(formatShortDate(null)).toBe('')
    expect(formatShortDate('')).toBe('')
    expect(formatShortDate('not-a-date')).toBe('')
  })
})

describe('formatCardUp', () => {
  it('单作者原样；多作者压缩为「首作者 等N」', () => {
    expect(formatCardUp(['甲'], '出处分区')).toBe('甲')
    expect(formatCardUp(['甲', '乙'], '出处分区')).toBe('甲 等2')
    expect(formatCardUp(['甲', '乙', '丙'], null)).toBe('甲 等3')
  })

  it('无作者（缺省/空数组/首元素空串）回退 source；两者皆无 → undefined（不渲染）', () => {
    expect(formatCardUp(undefined, 'cos')).toBe('cos')
    expect(formatCardUp(null, 'cos')).toBe('cos')
    expect(formatCardUp([], 'cos')).toBe('cos')
    expect(formatCardUp([''], 'cos')).toBe('cos') // 空串作者按无作者处理
    expect(formatCardUp(undefined, null)).toBeUndefined()
    expect(formatCardUp(undefined, '')).toBeUndefined() // 空串 source 同样不渲染
  })
})

describe('authorDisplayName', () => {
  it('type=cos 追加「 ·COS」标识（前置空格）；其余 type 原样', () => {
    expect(authorDisplayName({ displayName: '小明', type: 'cos' })).toBe('小明 ·COS')
    expect(authorDisplayName({ displayName: '小明', type: 'author' })).toBe('小明')
    expect(authorDisplayName({ displayName: '小明', type: null })).toBe('小明')
    expect(authorDisplayName({ displayName: '小明' })).toBe('小明')
  })

  it('displayName 缺省 → 空串兜底（cos 时仅剩标识串）', () => {
    expect(authorDisplayName({ displayName: null, type: 'author' })).toBe('')
    expect(authorDisplayName({ type: 'cos' })).toBe(' ·COS')
  })
})

describe('dirLabel', () => {
  it('路径末段：正/反斜杠及混用均可切分', () => {
    expect(dirLabel('photos/2026/08')).toBe('08')
    expect(dirLabel('photos\\2026\\08')).toBe('08')
    expect(dirLabel('a/b\\c')).toBe('c')
    expect(dirLabel('库根')).toBe('库根')
  })

  it('空路径 / null / undefined / 纯分隔符 → 库根', () => {
    expect(dirLabel('')).toBe('库根')
    expect(dirLabel(null)).toBe('库根')
    expect(dirLabel(undefined)).toBe('库根')
    expect(dirLabel('/')).toBe('库根')
  })
})

describe('formatDateTime', () => {
  it('毫秒时间戳 → M-D HH:mm（月/日不补零，时:分补零）', () => {
    const ts = new Date(2026, 8, 7, 9, 5).getTime() // 本地 2026-09-07 09:05
    expect(formatDateTime(ts)).toBe('9-7 09:05')
  })

  it('空值 / 非正数 / NaN → 空串', () => {
    expect(formatDateTime(undefined)).toBe('')
    expect(formatDateTime(null)).toBe('')
    expect(formatDateTime(0)).toBe('')
    expect(formatDateTime(-1)).toBe('')
    expect(formatDateTime(Number.NaN)).toBe('')
  })
})

describe('localDateKey', () => {
  it('y-m-d 数值串：月份取 getMonth() 原始 0 基值且不补零（分组键口径，非展示文案）', () => {
    const ts = new Date(2026, 8, 7, 12).getTime() // 本地 2026-09-07 → 键为 2026-8-7
    expect(localDateKey(ts)).toBe('2026-8-7')
  })

  it('空值 / 非正数 / NaN → 空串（调用方不分组）', () => {
    expect(localDateKey(undefined)).toBe('')
    expect(localDateKey(null)).toBe('')
    expect(localDateKey(0)).toBe('')
    expect(localDateKey(Number.NaN)).toBe('')
  })
})
