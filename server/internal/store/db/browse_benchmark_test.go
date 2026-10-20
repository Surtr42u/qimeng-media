package db

import (
	"context"
	"database/sql"
	"testing"
)

// 同一筛选/数据比较重复 COUNT+分桶与一次分桶，NULL 桶由 handler 只计总数。
func BenchmarkBrowseDateCountsLargeLibrary(b *testing.B) {
	q := New(seedBrowseLargeLibrary(b))
	params := CountAssetsByLocalDayParams{TzModifier: "+480 minutes", SourceIsOther: int64(0), IncludeCos: int64(1), CosOnly: int64(0)}
	for _, duplicate := range []bool{true, false} {
		name := "grouped_once"
		if duplicate {
			name = "count_then_group"
		}
		b.Run(name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				if duplicate {
					if _, err := q.CountAssetsFiltered(context.Background(), CountAssetsFilteredParams{SourceIsOther: int64(0), IncludeCos: int64(1), CosOnly: int64(0)}); err != nil {
						b.Fatal(err)
					}
				}
				rows, err := q.CountAssetsByLocalDay(context.Background(), params)
				if err != nil {
					b.Fatal(err)
				}
				var total int64
				for _, row := range rows {
					total += row.FileCount
				}
				if total != 50000 {
					b.Fatalf("total=%d", total)
				}
			}
		})
	}
}

// 显式合成大库探针，不进入常规 go test；同负载比较通用与索引日期路径。
// 记录计划/耗时，不把核心 SQL 的收益冒充整个 API 或 NAS 的 SLA。
func BenchmarkBrowseLargeLibrary(b *testing.B) {
	conn := seedBrowseLargeLibrary(b)
	benchmarkBrowseQueries(b, conn)
}

func seedBrowseLargeLibrary(b testing.TB) *sql.DB {
	b.Helper()
	conn := openMigratedDB(b)
	statements := []string{
		`INSERT INTO libraries(id,name,root_path,created_at) VALUES('large','synthetic','/synthetic-large','2026-01-01')`,
		`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<50000) INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) SELECT printf('x%06d',x),'large',x||'.jpg',x||'.jpg','image',1024,strftime('%Y-%m-%dT%H:%M:%fZ','2025-01-01','+'||x||' minutes'),'2026-01-01','2026-01-01' FROM n`,
		`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<250000) INSERT INTO view_events(asset_id,kind,session_id,started_at) SELECT printf('x%06d',x%50000+1),'open','s'||x,'2026-01-01T00:00:00.000Z' FROM n`,
	}
	for _, statement := range statements {
		if _, err := conn.Exec(statement); err != nil {
			b.Fatal(err)
		}
	}
	return conn
}

func benchmarkBrowseQueries(b *testing.B, conn *sql.DB) {
	for _, indexed := range []bool{false, true} {
		for _, cursor := range []bool{false, true} {
			name := "first_page"
			if cursor {
				name = "cursor_page"
			}
			if indexed {
				name = "indexed/" + name
			} else {
				name = "generic/" + name
			}
			b.Run(name, func(b *testing.B) {
				query := listAssetsFilteredDesc
				args := make([]any, 26)
				args[0], args[1], args[5], args[6] = "fileDate", "large", int64(1), int64(0)
				args[4] = int64(0)
				args[24], args[25] = sql.NullString{}, int64(61)
				if cursor {
					args[23] = "2025-01-18T08:40:00.000Z"
					args[24] = sql.NullString{String: "x025000", Valid: true}
				}
				if indexed {
					query = listAssetsFileDateDesc
					args = []any{int64(61), nil, "large", int64(1), int64(0), sql.NullString{}}
					if cursor {
						args[1] = "2025-01-18T08:40:00.000Z"
						args[5] = sql.NullString{String: "x025000", Valid: true}
					}
				}
				b.Logf("plan: %v", explainPlan(b, conn, query, args...))
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					rows, err := conn.Query(query, args...)
					if err != nil {
						b.Fatal(err)
					}
					count := 0
					for rows.Next() {
						count++
					}
					if err := rows.Err(); err != nil {
						b.Fatal(err)
					}
					if err := rows.Close(); err != nil {
						b.Fatal(err)
					}
					if count != 61 {
						b.Fatalf("首屏/游标应返回 61 行，实际 %d", count)
					}
				}
			})
		}
	}
}
