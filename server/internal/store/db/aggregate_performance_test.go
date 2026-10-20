package db

import (
	"context"
	"database/sql"
	_ "embed"
	"encoding/json"
	"strings"
	"testing"
)

// 强制候选计划用于暴露宽集合退化；实际装载选路看 store 的
// BenchmarkRecommendationInputLoading，不能将此处 current 当作宽流线上路径。
func BenchmarkRecommendCandidateInputs(b *testing.B) {
	conn := seedRecommendWorkload(b)
	for _, media := range []struct {
		name  string
		value any
		count int
	}{{"wide", nil, 20000}, {"video_1pct", "video", 200}, {"empty", "missing", 0}} {
		for _, baseline := range []bool{true, false} {
			name := "current"
			var backend DBTX = conn
			if baseline {
				name = "baseline"
				backend = recommendBaselineDB{conn}
			}
			q := New(backend)
			b.Run(media.name+"/"+name, func(b *testing.B) {
				b.ReportAllocs()
				for i := 0; i < b.N; i++ {
					rows, err := q.ListAssetsRecommendCandidateInput(context.Background(), ListAssetsRecommendCandidateInputParams{Day: "2026-01-01", MediaType: media.value, CosOnly: int64(0)})
					if err != nil {
						b.Fatal(err)
					}
					if len(rows) != media.count {
						b.Fatalf("candidates=%d", len(rows))
					}
					var payload any
					if !baseline {
						ids := make([]string, len(rows))
						for j := range rows {
							ids[j] = rows[j].AssetID
						}
						data, err := json.Marshal(ids)
						if err != nil {
							b.Fatal(err)
						}
						payload = string(data)
					}
					tags, err := q.ListTagsForAssets(context.Background(), payload)
					if err != nil {
						b.Fatal(err)
					}
					byAsset := make(map[string][]string, len(tags))
					for _, tag := range tags {
						byAsset[tag.AssetID] = append(byAsset[tag.AssetID], tag.Name)
					}
					for _, row := range rows {
						if len(byAsset[row.AssetID]) != 2 {
							b.Fatal("标签缺失")
						}
					}
					b.ReportMetric(float64(len(tags)), "tag-rows/op")
				}
			})
		}
	}
}

// 旧 SQL 只作等价 oracle/同负载基准，不参与运行时；后续优化必须保留它的领域语义。
//
//go:embed testdata/top_authors_baseline.sql
var topAuthorsBaseline string

//go:embed testdata/top_tags_baseline.sql
var topTagsBaseline string

//go:embed testdata/recommend_input_baseline.sql
var recommendInputBaseline string

//go:embed testdata/recommend_tags_baseline.sql
var recommendTagsBaseline string

func BenchmarkHotAggregatesSharedWorks(b *testing.B) {
	conn := seedSharedAuthorWorks(b)
	for _, statement := range []string{
		`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100) INSERT INTO tags(id,name,created_at) SELECT 't'||x,'Tag '||x,'2026-01-01' FROM n`,
		`INSERT INTO asset_tags(asset_id,tag_id,created_at) SELECT a.asset_id,t.id,'2026-01-01' FROM assets a CROSS JOIN tags t WHERE CAST(substr(a.asset_id,2) AS INTEGER)%5=CAST(substr(t.id,2) AS INTEGER)%5`,
	} {
		if _, err := conn.Exec(statement); err != nil {
			b.Fatal(err)
		}
	}
	for _, candidate := range []struct{ name, query string }{
		{"authors/baseline", topAuthorsBaseline}, {"authors/current", topOpenAuthors},
		{"tags/baseline", topTagsBaseline}, {"tags/current", topOpenTags},
	} {
		b.Run(candidate.name, func(b *testing.B) {
			args := []any{"", int64(20)}
			if strings.HasSuffix(candidate.name, "current") {
				args = []any{int64(20), ""}
			}
			benchmarkRawRows(b, conn, candidate.query, args, 20)
		})
	}
}

func benchmarkRawRows(b *testing.B, conn *sql.DB, query string, args []any, want int) {
	b.Helper()
	b.ReportAllocs()
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
		if count != want {
			b.Fatalf("rows=%d want=%d", count, want)
		}
	}
}

//go:embed testdata/recommend_workload.sql
var recommendWorkload string

func seedRecommendWorkload(b testing.TB) *sql.DB {
	b.Helper()
	conn := openMigratedDB(b)
	if _, err := conn.Exec(recommendWorkload); err != nil {
		b.Fatal(err)
	}
	return conn
}

func BenchmarkRecommendCandidateRead(b *testing.B) {
	conn := seedRecommendWorkload(b)
	for _, media := range []struct {
		name  string
		value any
		count int
	}{{"wide", nil, 20000}, {"video_1pct", "video", 200}} {
		for _, candidate := range []struct{ name, query string }{{"baseline", recommendInputBaseline}, {"current", listAssetsRecommendCandidateInput}} {
			b.Run(media.name+"/"+candidate.name, func(b *testing.B) {
				benchmarkRawRows(b, conn, candidate.query, []any{"2026-01-01", media.value, int64(0)}, media.count)
			})
		}
	}
}
