package recommend

import (
	"sort"
	"time"
)

// 排行周期窗口（DOMAIN_RULES §2：窗口内聚合，非 all 周期按 day ≥ cutoffDay
// 从按天数据求和/计数；day=24h week=7d month=30d quarter=90d year=365d，
// 常量集中一处——与 httpapi 的 period 枚举值（openapi enum
// day/week/month/quarter/year/all）同步，协议侧改动须同步此处）。
const (
	PeriodDay     = "day"
	PeriodWeek    = "week"
	PeriodMonth   = "month"
	PeriodQuarter = "quarter"
	PeriodYear    = "year"
	PeriodAll     = "all"
)

// rankPeriodWindows 是各周期对应的滚动窗口；不存在的键（含 all）无窗口
// （总榜语义 = 全量累计，与 2026-09-18 窗口化之前行为一致）。
var rankPeriodWindows = map[string]time.Duration{
	PeriodDay:     24 * time.Hour,
	PeriodWeek:    7 * 24 * time.Hour,
	PeriodMonth:   30 * 24 * time.Hour,
	PeriodQuarter: 90 * 24 * time.Hour,
	PeriodYear:    365 * 24 * time.Hour,
}

// PeriodWindow 返回周期对应的滚动窗口时长；all（或未知值——调用方已按
// openapi enum 校验）返回 false 表示无窗口。窗口到 cutoffDay 的换算
// （本地日历日取界）在调用方完成：窗口计数按天粒度聚合，Rank 不碰时钟。
func PeriodWindow(period string) (time.Duration, bool) {
	win, ok := rankPeriodWindows[period]
	return win, ok
}

// Rank 热度排行（纯函数，无 IO；DOMAIN_RULES §2）：
//
//   - period=all：热度 = Stats.ViewCount + Stats.PlayCount + LikeCount
//     全量累计降序（旧行为不变，无准入过滤，零窗口开销）；
//   - 非 all 周期：热度 = Item.Window 窗口内计数之和降序——窗口计数由
//     调用方按 day ≥ cutoffDay 从「资产×日」数据预算好传入，本函数只
//     排序；准入 = 窗口内热度 > 0（与窗口聚合自洽，不再按 LastViewedAt
//     时间戳单独过滤）；
//   - 同分均按 ModifiedAt 降序（新文件在前），排序稳定（相等时保持
//     输入序）。
//
// now 为历史签名保留（时间由调用方注入的可回放约定）；两条路径的准入
// 都不再直接比较时间戳，窗口边界已折算在 Window 计数里。
func Rank(items []Item, period string, now time.Time) []Item {
	if len(items) == 0 {
		return nil
	}
	if _, windowed := rankPeriodWindows[period]; !windowed {
		out := make([]Item, len(items))
		copy(out, items)
		sortByHeat(out, func(it *Item) int { return heatOf(it) })
		return out
	}
	out := make([]Item, 0, len(items))
	for i := range items {
		if items[i].Window.Heat() > 0 {
			out = append(out, items[i])
		}
	}
	sortByHeat(out, func(it *Item) int { return it.Window.Heat() })
	return out
}

// sortByHeat 按给定热度函数降序稳定排序；同分按 ModifiedAt 降序（新文件
// 在前，DOMAIN_RULES §2 同分口径）。
func sortByHeat(items []Item, heat func(*Item) int) {
	sort.SliceStable(items, func(i, j int) bool {
		hi, hj := heat(&items[i]), heat(&items[j])
		if hi != hj {
			return hi > hj
		}
		return items[i].ModifiedAt.After(items[j].ModifiedAt)
	})
}

// heatOf 总榜热度分（全量累计 view + play + like）。
func heatOf(it *Item) int {
	return it.Stats.ViewCount + it.Stats.PlayCount + it.LikeCount
}
