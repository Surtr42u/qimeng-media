package statsread

import (
	"time"

	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/store"
)

// 趋势取数窗口：api/openapi.yaml /stats/trends 的 range 枚举各自回看的
// 数据宽度（协议侧窗口语义改动须同步此处，反之亦然）。窗口与 stats 固定
// 分桶粒度一一对应（DOMAIN_RULES §5：day 按天 / week 按周 / month 按月 /
// quarter 按季 / year 按年；all = 全跨度不设窗口，由动态粒度选择覆盖）。
const (
	trendDayWindowDays      = 30 // range=day：近 30 天
	trendWeekWindowDays     = 84 // range=week：近 12 周（12×7 天）
	trendMonthWindowMonths  = 24 // range=month：近 24 个月
	trendQuarterWindowMonth = 24 // range=quarter：近 8 个季度（8×3 = 24 个月）
	trendYearWindowYears    = 5  // range=year：近 5 年
	trend7dWindowDays       = 7  // range=7d：近 7 天逐日桶
	trend90dWindowDays      = 90 // range=90d：近 90 天逐日桶
)

func WindowStart(rng stats.Range, today time.Time) time.Time {
	local := today.Local()
	switch rng {
	case stats.RangeDay:
		return local.AddDate(0, 0, -(trendDayWindowDays - 1))
	case stats.Range7d:
		return local.AddDate(0, 0, -(trend7dWindowDays - 1))
	case stats.Range90d:
		return local.AddDate(0, 0, -(trend90dWindowDays - 1))
	case stats.RangeWeek:
		return local.AddDate(0, 0, -(trendWeekWindowDays - 1))
	case stats.RangeMonth:
		return local.AddDate(0, -trendMonthWindowMonths, 0)
	case stats.RangeQuarter:
		return local.AddDate(0, -trendQuarterWindowMonth, 0)
	case stats.RangeYear:
		return local.AddDate(-trendYearWindowYears, 0, 0)
	default:
		return time.Time{}
	}
}

func OpenWindowStart(rng stats.Range, now time.Time) string {
	start := WindowStart(rng, now)
	if start.IsZero() {
		return ""
	}
	local := start.Local()
	return store.FormatTimestamp(time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, local.Location()))
}
func DayBounds(now time.Time) (string, string) {
	local := now.Local()
	start := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, local.Location())
	return store.FormatTimestamp(start), store.FormatTimestamp(start.AddDate(0, 0, 1))
}
