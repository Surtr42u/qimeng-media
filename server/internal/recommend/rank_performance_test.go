package recommend

import (
	"fmt"
	"testing"
	"time"
)

func rankPerformanceItems(n int) []Item {
	items := make([]Item, n)
	for i := range items {
		it := makeItem(fmt.Sprintf("rank-%06d", i), "image", fixedNow)
		it.ModifiedAt = fixedNow.Add(-time.Duration(i%137) * time.Hour)
		it.Stats = Stats{ViewCount: (i * 71) % 53, PlayCount: i % 11}
		it.LikeCount = i % 7
		if i%5 != 0 {
			it.Window = WindowCounts{ViewCount: (i * 29) % 23, PlayCount: i % 5, LikeCount: i % 3}
		}
		items[i] = it
	}
	return items
}

// 仅度量当前端点返回 200 项所需的纯排序；不含输入查询或 HTTP 装配。
func BenchmarkRankPage(b *testing.B) {
	for _, n := range []int{2000, 20000} {
		items := rankPerformanceItems(n)
		for _, period := range []string{PeriodAll, PeriodWeek} {
			b.Run(fmt.Sprintf("n=%d/%s", n, period), func(b *testing.B) {
				b.ReportAllocs()
				for i := 0; i < b.N; i++ {
					got := RankTop(items, period, fixedNow, 200)
					if len(got) != 200 {
						b.Fatal("排行结果缺项")
					}
				}
			})
		}
	}
}
