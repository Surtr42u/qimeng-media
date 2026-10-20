package recommend

import (
	"fmt"
	"math/rand"
	"reflect"
	"sort"
	"testing"
	"time"
)

// 保留优化前的准入与全量稳定排序，独立约束 Top-K 的堆方向、同分序和完整字段。
func rankFullBaseline(items []Item, period string) []Item {
	if len(items) == 0 {
		return nil
	}
	_, windowed := rankPeriodWindows[period]
	out := make([]Item, 0, len(items))
	heat := func(it Item) int { return it.Stats.ViewCount + it.Stats.PlayCount + it.LikeCount }
	if windowed {
		heat = func(it Item) int { return it.Window.ViewCount + it.Window.PlayCount + it.Window.LikeCount }
	}
	for _, it := range items {
		if !windowed || heat(it) > 0 {
			out = append(out, it)
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		if hi, hj := heat(out[i]), heat(out[j]); hi != hj {
			return hi > hj
		}
		return out[i].ModifiedAt.After(out[j].ModifiedAt)
	})
	return out
}

func TestRankTopMatchesFullStableRanking(t *testing.T) {
	for _, n := range []int{0, 1, 2, 257, 1009} {
		items := rankPerformanceItems(n)
		for i := range items {
			items[i].Tags = []string{fmt.Sprintf("tag-%d", i)}
			if i%2 == 0 {
				items[i].ModifiedAt = items[i].ModifiedAt.In(time.FixedZone("offset", 8*60*60))
			}
		}
		rng := rand.New(rand.NewSource(42))
		rng.Shuffle(len(items), func(i, j int) { items[i], items[j] = items[j], items[i] })
		before := append([]Item(nil), items...)
		for _, period := range []string{PeriodAll, PeriodDay, PeriodWeek, PeriodMonth, PeriodQuarter, PeriodYear, "unknown"} {
			full := rankFullBaseline(items, period)
			for _, limit := range []int{-1, 0, 1, 2, 60, 200, n - 1, n, n + 1, int(^uint(0) >> 1)} {
				want := full
				if limit > 0 && limit < len(want) {
					want = want[:limit]
				}
				if got := RankTop(items, period, fixedNow, limit); !reflect.DeepEqual(got, want) {
					t.Fatalf("n=%d period=%s limit=%d 排序或完整字段改变", n, period, limit)
				}
			}
		}
		if n > 0 && !reflect.DeepEqual(items, before) {
			t.Fatal("Top-K 不得改写输入")
		}
	}
}

func TestRankTopKeepsInputOrderOnBusinessTies(t *testing.T) {
	items := rankPerformanceItems(300)
	for i := range items {
		items[i].Stats = Stats{ViewCount: 1}
		items[i].LikeCount = 0
		items[i].ModifiedAt = fixedNow
		items[i].Window = WindowCounts{ViewCount: 1}
	}
	for _, period := range []string{PeriodAll, PeriodWeek} {
		if got := RankTop(items, period, fixedNow, 50); !reflect.DeepEqual(got, items[:50]) {
			t.Fatal("业务键同分时必须保留输入序，不能按资产 ID 重排")
		}
	}
	for i := range items {
		items[i].Window = WindowCounts{}
	}
	if got := RankTop(items, PeriodWeek, fixedNow, 50); got == nil || len(got) != 0 {
		t.Fatal("窗口无活动应保持空切片")
	}
}

func BenchmarkRankSelectionShapes(b *testing.B) {
	for _, shape := range []string{"random", "best_first", "worst_first", "deep_page"} {
		items := rankPerformanceItems(20000)
		limit := 200
		switch shape {
		case "random", "deep_page":
			rng := rand.New(rand.NewSource(42))
			rng.Shuffle(len(items), func(i, j int) { items[i], items[j] = items[j], items[i] })
			if shape == "deep_page" {
				limit = 10000
			}
		default:
			items = rankFullBaseline(items, PeriodAll)
			if shape == "worst_first" {
				for i, j := 0, len(items)-1; i < j; i, j = i+1, j-1 {
					items[i], items[j] = items[j], items[i]
				}
			}
		}
		for _, baseline := range []bool{true, false} {
			b.Run(fmt.Sprintf("%s/full=%t", shape, baseline), func(b *testing.B) {
				b.ReportAllocs()
				for i := 0; i < b.N; i++ {
					var got []Item
					if baseline {
						got = Rank(items, PeriodAll, fixedNow)[:limit]
					} else {
						got = RankTop(items, PeriodAll, fixedNow, limit)
					}
					if len(got) != limit {
						b.Fatal("排行缺项")
					}
				}
			})
		}
	}
}
