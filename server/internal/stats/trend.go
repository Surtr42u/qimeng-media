package stats

import (
	"fmt"
	"time"
)

// Range 是趋势范围枚举（api/openapi.yaml /stats/trends 的 range 参数取值集；
// 协议侧增删取值须同步此处与 httpapi 的窗口常量）。
type Range string

const (
	RangeDay     Range = "day"     // 近 30 天按天
	RangeWeek    Range = "week"    // 近 12 周按周
	RangeMonth   Range = "month"   // 近 24 月按月
	RangeQuarter Range = "quarter" // 近 8 季按季
	RangeYear    Range = "year"    // 近 5 年按年
	RangeAll     Range = "all"     // 全跨度动态粒度（DOMAIN_RULES §5）
	Range7d      Range = "7d"      // 近 7 天逐日（取数窗口宽度见 httpapi 常量）
	Range90d     Range = "90d"     // 近 90 天逐日（取数窗口宽度见 httpapi 常量）
)

// 动态分桶粒度选择阈值（DOMAIN_RULES §5「趋势图『全部』分桶口径」逐字遵守：
// ≤12 周按周 / 12 周~24 月按月 / 更长按季）。
const (
	// maxWeekBuckets 数据跨度不超过的周桶数（≤12 按周分桶）。
	maxWeekBuckets = 12
	// maxMonthSpan 数据跨度不超过的自然月数（≤24 按月分桶，否则按季）。
	maxMonthSpan = 24
)

// dayLayout 是「日」输入/输出的格式（store.DayLayout 同值——stats 包不依赖
// store，格式契约由调用方保证：DailyRow.Day 必须是 store.FormatDay 产物）。
const dayLayout = "2006-01-02"

// weekDays 是一个自然周的长度（周桶推进步长）。
const weekDays = 7

// hoursPerDay 周/日粒度换算的小时数（24 的具名化；分桶口径本身见
// DOMAIN_RULES §5，此处仅消除裸数字）。
const hoursPerDay = 24

// DailyRow 是「文件×天」聚合行（唯一真相源口径，DOMAIN_RULES §5）：
// 每文件每天一行，Day 为本地日历日 yyyy-MM-dd（store.FormatDay 产物）。
type DailyRow struct {
	Day       string
	ViewCount int64
	PlayCount int64
	Seconds   int64
}

// TrendBucket 是趋势折线的一个数据点：Label 为展示标签，Start/End 为桶
// 首日/末日（本地日历日 yyyy-MM-dd，含端点），三项计数为桶内聚合值。
type TrendBucket struct {
	Label     string
	Start     string
	End       string
	ViewCount int64
	PlayCount int64
	Seconds   int64
}

// granularity 是分桶粒度（内部枚举，Range 的映射结果）。
type granularity int

const (
	granDay granularity = iota
	granWeek
	granMonth
	granQuarter
	granYear
)

// BuildTrendBuckets 把「文件×天」聚合行分桶为趋势折线数据点（纯函数）。
//
// 口径（DOMAIN_RULES §5，与旧项目 StatsFormatHelper 语义逐条对齐）：
//   - RangeAll 动态选粒度：跨度 ≤12 周按周 / 12 周~24 月按月 / 更长按季；
//     固定 Range 按天/周/月/季/年固定分桶（取数窗口由调用方限制）。
//   - 桶从最早数据所在周期铺到当前周期，全量不丢弃；数据落在未来的行
//     （时钟漂移兜底）也归桶——结束周期取 max(today, 最晚数据) 所在周期，
//     因此各桶之和 = 全量总和，无条件守恒。
//   - 周对齐用「距周一回退」法，与系统 locale 无关（旧项目 v1.16 修复）。
//   - 数据不足 2 个周期时向前补一个前导 0 值桶，保证折线至少两点可画。
//   - 空数据返回 nil（前端显示「暂无趋势数据」）。
//
// 输入契约：rows[i].Day 必须是本地日历日 yyyy-MM-dd（store.FormatDay 产物）；
// 非法格式的行被跳过（契约破坏时守恒性不再保证，属调用方 bug）。
// today 取其本地日（本地时区日界）；rows 无需预排序。
func BuildTrendBuckets(rows []DailyRow, r Range, today time.Time) []TrendBucket {
	if len(rows) == 0 {
		return nil
	}
	// 解析行日期并求跨度（本地日零点对齐，消除当日时刻差异）
	earliest, latest, sums := parseRows(rows)
	if earliest.IsZero() {
		return nil // 全部行非法：等价空输入
	}
	todayDay := dayStartLocal(today)
	end := todayDay
	if latest.After(end) {
		end = latest // 未来行兜底：桶铺到最晚数据，总和守恒
	}

	gran := chooseGranularity(r, earliest, end)
	first, last := align(gran, earliest), align(gran, end)
	starts := bucketStarts(first, last, gran)

	labels := bucketLabels(starts, gran)
	byStart := make(map[time.Time]*bucketTotals, len(starts))
	for _, it := range sums {
		start := align(gran, it.day)
		agg := byStart[start]
		if agg == nil {
			agg = &bucketTotals{}
			byStart[start] = agg
		}
		agg.view += it.view
		agg.play += it.play
		agg.secs += it.secs
	}

	out := make([]TrendBucket, 0, len(starts))
	for i, start := range starts {
		var view, play, secs int64
		if agg := byStart[start]; agg != nil {
			view, play, secs = agg.view, agg.play, agg.secs
		}
		out = append(out, TrendBucket{
			Label:     labels[i],
			Start:     start.Format(dayLayout),
			End:       bucketEnd(start, gran).Format(dayLayout),
			ViewCount: view,
			PlayCount: play,
			Seconds:   secs,
		})
	}
	return out
}

// bucketTotals 是桶内三项计数的累加器（view / play / browse seconds）。
type bucketTotals struct {
	view, play, secs int64
}

// rowAgg 是单日聚合值（同一「天」多文件多行，先并成天再归桶）。
type rowAgg struct {
	day        time.Time
	view, play int64
	secs       int64
}

// parseRows 解析全部行的本地日并按「天」预聚合，返回跨度两端与天聚合表。
func parseRows(rows []DailyRow) (earliest, latest time.Time, perDay []rowAgg) {
	byDay := make(map[time.Time]*rowAgg, len(rows))
	for _, row := range rows {
		d, err := time.ParseInLocation(dayLayout, row.Day, time.Local)
		if err != nil {
			continue // 输入契约破坏：跳过（见 BuildTrendBuckets 契约注释）
		}
		d = dayStartLocal(d)
		agg := byDay[d]
		if agg == nil {
			agg = &rowAgg{day: d}
			byDay[d] = agg
		}
		agg.view += row.ViewCount
		agg.play += row.PlayCount
		agg.secs += row.Seconds
		if earliest.IsZero() || d.Before(earliest) {
			earliest = d
		}
		if latest.IsZero() || d.After(latest) {
			latest = d
		}
	}
	perDay = make([]rowAgg, 0, len(byDay))
	for _, agg := range byDay {
		perDay = append(perDay, *agg)
	}
	return earliest, latest, perDay
}

// chooseGranularity 决定分桶粒度：固定 Range 直接映射；RangeAll 按
// DOMAIN_RULES §5 阈值动态选择（≤12 周桶按周 / ≤24 自然月按月 / 更长按季）。
func chooseGranularity(r Range, earliest, end time.Time) granularity {
	switch r {
	case RangeDay, Range7d, Range90d: // 7d/90d 与 day 同为逐日分桶
		return granDay
	case RangeWeek:
		return granWeek
	case RangeMonth:
		return granMonth
	case RangeQuarter:
		return granQuarter
	case RangeYear:
		return granYear
	default: // RangeAll 与未知值：动态（未知值兜底到全口径，不报错丢数据）
		weekCount := int(align(granWeek, end).Sub(align(granWeek, earliest)) / (weekDays * hoursPerDay * time.Hour))
		if weekCount+1 <= maxWeekBuckets {
			return granWeek
		}
		if monthsBetween(earliest, end) <= maxMonthSpan {
			return granMonth
		}
		return granQuarter
	}
}

// align 把本地日对齐到所在周期起点（天=自身；周=回退到周一，「距周一回退」
// 法与 locale 无关；月/季=月初；年=年初）。
func align(g granularity, day time.Time) time.Time {
	switch g {
	case granWeek:
		// (weekday+6)%7 = 距周一的天数（周一 0 … 周日 6），等价旧实现
		// (Calendar.DAY_OF_WEEK+5)%7 的 locale 无关修复版。
		offset := (int(day.Weekday()) + 6) % weekDays
		return day.AddDate(0, 0, -offset)
	case granMonth:
		return time.Date(day.Year(), day.Month(), 1, 0, 0, 0, 0, time.Local)
	case granQuarter:
		return time.Date(day.Year(), day.Month()-time.Month((int(day.Month())-1)%3), 1, 0, 0, 0, 0, time.Local)
	case granYear:
		return time.Date(day.Year(), time.January, 1, 0, 0, 0, 0, time.Local)
	default:
		return day // granDay
	}
}

// stepBack 从周期起点回退一个周期（月/季用月初锚点，AddDate 月溢出无歧义）。
func stepBack(g granularity, start time.Time) time.Time {
	switch g {
	case granDay:
		return start.AddDate(0, 0, -1)
	case granWeek:
		return start.AddDate(0, 0, -weekDays)
	case granMonth:
		return start.AddDate(0, -1, 0)
	case granQuarter:
		return start.AddDate(0, -3, 0)
	default:
		return start.AddDate(-1, 0, 0)
	}
}

// bucketEnd 计算桶末日（含）：下一周期起点的前一天。锚点都是周期起点
// （月初/周一 1 号），减一天即得精确末日，不依赖各月天数表。
func bucketEnd(start time.Time, g granularity) time.Time {
	switch g {
	case granDay:
		return start
	case granWeek:
		return start.AddDate(0, 0, weekDays-1)
	case granMonth:
		return start.AddDate(0, 1, 0).AddDate(0, 0, -1)
	case granQuarter:
		return start.AddDate(0, 3, 0).AddDate(0, 0, -1)
	default:
		return start.AddDate(1, 0, 0).AddDate(0, 0, -1)
	}
}

// bucketStarts 生成升序桶起点序列：从最早数据所在周期铺到当前周期；
// 仅 1 个周期时向前补一个 0 值桶（旧 bucketSequence 语义：折线至少两点）。
func bucketStarts(first, last time.Time, g granularity) []time.Time {
	descending := []time.Time{last}
	for bucket := last; bucket.After(first); {
		bucket = stepBack(g, bucket)
		descending = append(descending, bucket)
	}
	if len(descending) == 1 { // 数据不足 2 个周期：补前导 0 桶
		descending = append(descending, stepBack(g, last))
	}
	for i, j := 0, len(descending)-1; i < j; i, j = i+1, j-1 { // 反转为升序
		descending[i], descending[j] = descending[j], descending[i]
	}
	return descending
}

// bucketLabels 为桶起点序列生成展示标签（旧 StatsFormatHelper 格式）：
// 天/周=MM/dd；月=同年 MM月、跨年 yy/MM；季=yy/Qn；年=yyyy。
// 月标签格式按整个序列首末是否同年统一选择（旧实现 bucketize 前判断）。
func bucketLabels(starts []time.Time, g granularity) []string {
	out := make([]string, len(starts))
	sameYear := starts[0].Year() == starts[len(starts)-1].Year()
	for i, start := range starts {
		switch g {
		case granDay, granWeek:
			out[i] = start.Format("01/02")
		case granMonth:
			if sameYear {
				out[i] = fmt.Sprintf("%02d月", int(start.Month()))
			} else {
				out[i] = fmt.Sprintf("%02d/%02d", start.Year()%100, int(start.Month()))
			}
		case granQuarter:
			out[i] = fmt.Sprintf("%02d/Q%d", start.Year()%100, (int(start.Month())-1)/3+1)
		default:
			out[i] = fmt.Sprintf("%04d", start.Year())
		}
	}
	return out
}

// monthsBetween 计算两个本地日跨越的自然月数（含首尾月，旧实现同口径）：
// 同月 = 1，相邻月 = 2。
func monthsBetween(earliest, end time.Time) int {
	return (end.Year()-earliest.Year())*12 + int(end.Month()-earliest.Month()) + 1
}

// dayStartLocal 取 t 在本地时区的当日零点（本地日历日口径，DOMAIN_RULES §5）。
func dayStartLocal(t time.Time) time.Time {
	local := t.Local()
	return time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, local.Location())
}
