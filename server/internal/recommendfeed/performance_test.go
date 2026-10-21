package recommendfeed

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 显式 benchmark 才装载 2 万资产/40 万事件；复用 store 的同一合成负载，
// 相对路径仅是测试夹具契约，运行时从不读取仓库文件。
func BenchmarkRecommendationSnapshot(b *testing.B) {
	conn, err := store.Open(filepath.Join(b.TempDir(), "synthetic.db"))
	if err != nil {
		b.Fatal(err)
	}
	b.Cleanup(func() {
		if err := conn.Close(); err != nil {
			b.Error(err)
		}
	})
	if err := store.Migrate(conn); err != nil {
		b.Fatal(err)
	}
	fixture, err := os.ReadFile("../store/db/testdata/recommend_workload.sql")
	if err != nil {
		b.Fatal(err)
	}
	if _, err := conn.Exec(string(fixture)); err != nil {
		b.Fatal(err)
	}
	q := db.New(conn)
	key := Key{Day: "2026-01-02", Seed: 42}
	now := time.Date(2026, 1, 2, 12, 0, 0, 0, time.UTC)
	for _, warm := range []bool{false, true} {
		name, offset := "cold_first", 0
		if warm {
			name, offset = "warm_next", 60
		}
		b.Run(name, func(b *testing.B) {
			cache := NewCache()
			if err := cache.Warm(context.Background(), q, key, nil, now); err != nil {
				b.Fatal(err)
			}
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				if !warm {
					cache.Clear()
				}
				page, _, err := cache.LoadPage(context.Background(), q, key, nil, now, offset, 60)
				if err != nil {
					b.Fatal(err)
				}
				if len(page) != 60 {
					b.Fatal("incomplete page")
				}
			}
		})
	}
}
