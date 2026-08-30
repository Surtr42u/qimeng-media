package recommend

import (
	"sort"
	"time"
)

// 排行周期窗口（DOMAIN_RULES §2：按 ViewEvent 时间戳过滤；
// day=24h week=7d month=30d year=365d，常量集中一处——与 httpapi 的
// period 枚举值（openapi enum day/week/month/year/all）同步，协议侧
// 改动须同步此处）。
const (
	PeriodDay   = "day"
	PeriodWeek  = "week"
	PeriodMonth = "month"
	PeriodYear  = "year"
	PeriodAll   = "all"
)

// rankPeriodWindows 是各周期对应的过滤窗口；不存在的键（含 all）不
// 过滤（总榜语义）。
var rankPeriodWindows = map[string]time.Duration{
	PeriodDay:   24 * time.Hour,
	PeriodWeek:  7 * 24 * time.Hour,
	PeriodMonth: 30 * 24 * time.Hour,
	PeriodYear:  365 * 24 * time.Hour,
}

// Rank 热度排行（纯函数，无 IO）：
//
//   - 热度 = viewCount + playCount + likeCount 降序，同分按 ModifiedAt
//     降序（新文件在前），排序稳定（相等时保持输入序）；
//   - 非 all 周期先过滤：仅保留 lastViewedAt >= now − 窗口 的条目
//     （浏览量来源是 open 事件（MAX(started_at)），与旧项目 keysInPeriod
//     的 statKeys 口径一致——历史事件流在本服务端已聚合进 lastViewedAt，
//     无需再展开 history 表）；
//   - 陌生 period 值不做过滤（保守：调用方已按 openapi enum 校验）。
func Rank(items []Item, period string, now time.Time) []Item {
	if len(items) == 0 {
		return nil
	}
	filtered := items
	if win, ok := rankPeriodWindows[period]; ok {
		cutoff := now.Add(-win)
		filtered = make([]Item, 0, len(items))
		for i := range items {
			if lv := items[i].Stats.LastViewedAt; lv != nil && !lv.Before(cutoff) {
				filtered = append(filtered, items[i])
			}
		}
	}
	out := make([]Item, len(filtered))
	copy(out, filtered)
	sort.SliceStable(out, func(i, j int) bool {
		hi := heatOf(&out[i])
		hj := heatOf(&out[j])
		if hi != hj {
			return hi > hj
		}
		return out[i].ModifiedAt.After(out[j].ModifiedAt)
	})
	return out
}

// heatOf 排行热度总分（view + play + like，DOMAIN_RULES §2 逐字遵守）。
func heatOf(it *Item) int {
	return it.Stats.ViewCount + it.Stats.PlayCount + it.LikeCount
}
