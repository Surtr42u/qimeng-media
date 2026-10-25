package recommend

import (
	"fmt"
	"testing"
)

func fullOrderBenchmarkItems() []Item {
	items := make([]Item, 20000)
	for i := range items {
		kind := "image"
		if i%5 == 0 {
			kind = "video"
		}
		items[i] = makeItem(fmt.Sprintf("asset-%05d", i), kind, fixedNow)
		items[i].FileName = fmt.Sprintf("作品_%d_系列_样本.jpg", i%100)
		items[i].Tags = []string{"手动标签", fmt.Sprintf("tag-%d", i%50)}
		items[i].Stats = Stats{ViewCount: i % 7, PlayCount: i % 3, BrowseSeconds: int64(i % 300)}
		items[i].LikeCount = i % 4
		items[i].ShownToday = i % 3
	}
	return items
}

// 两种方案都生成完整顺序，旧出口完整复制 Item 后提 ID，不使用小页基准冒充收益。
func BenchmarkRecommendIDOrder(b *testing.B) {
	items := fullOrderBenchmarkItems()
	p := Params{Now: fixedNow, Seed: 42}
	for _, direct := range []bool{false, true} {
		name := "items-then-ids"
		if direct {
			name = "direct-ids"
		}
		b.Run(name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				var ids []string
				if direct {
					ids = RecommendIDs(items, p)
				} else {
					ordered := Recommend(items, p)
					ids = make([]string, len(ordered))
					for i, it := range ordered {
						ids[i] = it.AssetID
					}
				}
				if len(ids) != len(items) {
					b.Fatal("incomplete order")
				}
			}
		})
	}
}

func BenchmarkRecommendPreparedSeeds(b *testing.B) {
	items := fullOrderBenchmarkItems()
	p := Params{Now: fixedNow}
	for _, shared := range []bool{false, true} {
		name := "prepare-per-seed"
		if shared {
			name = "shared-preparation"
		}
		b.Run(name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				var prepared *Prepared
				if shared {
					prepared = Prepare(items, p)
				}
				for _, seed := range []int{1, 0} {
					if !shared {
						prepared = Prepare(items, p)
					}
					if len(prepared.IDs(seed, 0)) != len(items) {
						b.Fatal("incomplete order")
					}
				}
			}
		})
	}
	b.Run("preparation-only", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			if len(Prepare(items, p).items) != len(items) {
				b.Fatal("incomplete preparation")
			}
		}
	})
	prepared := Prepare(items, p)
	b.Run("seed-order-only", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			if len(prepared.IDs(42, 0)) != len(items) {
				b.Fatal("incomplete order")
			}
		}
	})
}
