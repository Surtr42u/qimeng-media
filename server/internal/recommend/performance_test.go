package recommend

import (
	"fmt"
	"testing"
)

// 只度量纯算法；无 DB/HTTP，固定时间、输入与 seed，便于同负载前后比较。
func BenchmarkRecommendLargeLibrary(b *testing.B) {
	for _, scenario := range []struct {
		name  string
		mixed bool
	}{{"images", false}, {"mixed", true}} {
		items := make([]Item, 20000)
		for i := range items {
			media := "image"
			if scenario.mixed && i%5 == 0 {
				media = "video"
			}
			items[i] = makeItem(fmt.Sprintf("asset-%05d", i), media, fixedNow)
			items[i].FileName = fmt.Sprintf("作品_%d_系列_样本.jpg", i%100)
			items[i].Tags = []string{"手动标签", fmt.Sprintf("tag-%d", i%50)}
			items[i].Stats = Stats{ViewCount: i % 7, PlayCount: i % 3, BrowseSeconds: int64(i % 300)}
			items[i].LikeCount = i % 4
			items[i].ShownToday = i % 3
		}
		b.Run(scenario.name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				got := Recommend(items, Params{Now: fixedNow, Seed: 42, Limit: 200})
				if len(got) != 200 {
					b.Fatal("推荐页缺项")
				}
			}
		})
	}
}
