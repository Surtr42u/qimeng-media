package scanner

import (
	"path/filepath"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"testing"
)

// 只测 SQL 装载+路径索引，不把该结果当作含文件遍历/移动的完整扫描收益。
func BenchmarkScanSnapshotLoad(b *testing.B) {
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
	_, err = conn.Exec(`INSERT INTO libraries(id,name,root_path,created_at) VALUES('snapshot','synthetic','/synthetic-snapshot','2026-01-01');
        WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<20000)
        INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at)
        SELECT printf('x%06d',x),'snapshot',x||'.jpg',x||'.jpg','image',1024,'2026-01-01','2026-01-01','2026-01-01' FROM n;`)
	if err != nil {
		b.Fatal(err)
	}
	q := db.New(conn)
	for _, narrow := range []bool{false, true} {
		name := "full-row"
		if narrow {
			name = "narrow-row"
		}
		b.Run(name, func(b *testing.B) {
			b.ReportAllocs()
			for b.Loop() {
				if narrow {
					rows, err := q.ListScanAssetSnapshots(b.Context(), "snapshot")
					if err != nil {
						b.Fatal(err)
					}
					if len(indexScanAssets(rows)) != 20000 {
						b.Fatal("incomplete snapshot")
					}
				} else {
					rows, err := q.ListAssetsByLibrary(b.Context(), "snapshot")
					if err != nil {
						b.Fatal(err)
					}
					index := make(map[string]int, len(rows))
					for i := range rows {
						index[rows[i].RelPath] = i
					}
					if len(index) != 20000 {
						b.Fatal("incomplete full snapshot")
					}
				}
			}
		})
	}
}
