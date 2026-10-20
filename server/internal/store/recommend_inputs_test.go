package store

import (
	"context"
	"database/sql"
	_ "embed"
	"fmt"
	"reflect"
	"slices"
	"strings"
	"testing"

	"qimeng-media/server/internal/store/db"
)

// Canonical fixture is shared with db-level raw-query benchmarks.
//
//go:embed db/testdata/recommend_workload.sql
var recommendReadWorkload string

func BenchmarkRecommendationInputLoading(b *testing.B) {
	conn, q := openTestDB(b)
	if _, err := conn.Exec(recommendReadWorkload); err != nil {
		b.Fatal(err)
	}
	for _, scenario := range []struct {
		name, media string
		count       int
	}{
		{"wide", "", 20000}, {"video_1pct", "video", 200}, {"image_99pct", "image", 19800}, {"empty", "missing", 0}, {"half", "video", 10000},
	} {
		if scenario.name == "half" {
			if _, err := conn.Exec(`UPDATE assets SET media_type=CASE WHEN CAST(substr(asset_id,2) AS INTEGER)%2=0 THEN 'video' ELSE 'image' END`); err != nil {
				b.Fatal(err)
			}
		}
		media := sql.NullString{String: scenario.media, Valid: scenario.media != ""}
		for _, baseline := range []bool{true, false} {
			name := "adaptive"
			if baseline {
				name = "baseline"
			}
			b.Run(scenario.name+"/"+name, func(b *testing.B) {
				b.ReportAllocs()
				for i := 0; i < b.N; i++ {
					var input RecommendationInputs
					var err error
					if baseline {
						input.Rows, err = q.ListAssetsRecommendInput(context.Background(), db.ListAssetsRecommendInputParams{Day: "2026-01-01", MediaType: media, CosOnly: int64(0)})
						if err != nil {
							b.Fatal(err)
						}
						tags, tagErr := q.ListAllAssetTags(context.Background())
						if tagErr != nil {
							b.Fatal(tagErr)
						}
						input.TagsByAsset = groupRecommendationTags(tags)
					} else {
						input, err = LoadRecommendationInputs(context.Background(), q, "2026-01-01", media, 0)
					}
					if err != nil {
						b.Fatal(err)
					}
					if len(input.Rows) != scenario.count {
						b.Fatalf("candidates=%d", len(input.Rows))
					}
					for _, row := range input.Rows {
						if len(input.TagsByAsset[row.AssetID]) != 2 {
							b.Fatal("标签缺失")
						}
					}
					tags := 0
					for _, names := range input.TagsByAsset {
						tags += len(names)
					}
					b.ReportMetric(float64(tags), "tag-rows/op")
				}
			})
		}
	}
}

type recommendReadTrace struct {
	*sql.DB
	names []string
	stale bool
}

func (r *recommendReadTrace) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	r.names = append(r.names, strings.SplitN(query, "\n", 2)[0])
	return r.DB.QueryContext(ctx, query, args...)
}

func (r *recommendReadTrace) QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row {
	r.names = append(r.names, strings.SplitN(query, "\n", 2)[0])
	if r.stale && strings.HasPrefix(query, "-- name: CountRecommendScope") {
		return r.DB.QueryRowContext(ctx, `SELECT 4,0`)
	}
	return r.DB.QueryRowContext(ctx, query, args...)
}

func TestRecommendationInputPlanPreservesAllCandidates(t *testing.T) {
	conn, q := openTestDB(t)
	lib := newLibrary(t, q)
	for i := 0; i < 4; i++ {
		media := "image"
		if i == 0 {
			media = "video"
		}
		if _, err := q.UpsertAsset(context.Background(), db.UpsertAssetParams{AssetID: fmt.Sprintf("synthetic-%d", i), LibraryID: lib,
			RelPath: fmt.Sprintf("%d.jpg", i), FileName: fmt.Sprintf("%d.jpg", i), MediaType: media, SizeBytes: 1024,
			Mtime: "2026-01-01", CreatedAt: "2026-01-01", UpdatedAt: "2026-01-01"}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := conn.Exec(`INSERT INTO tags(id,name,created_at) VALUES('a','Alpha','2026-01-01'); INSERT INTO asset_tags(asset_id,tag_id,created_at) SELECT asset_id,'a','2026-01-01' FROM assets`); err != nil {
		t.Fatal(err)
	}
	for _, scenario := range []struct {
		media string
		count int
		plan  string
		stale bool
	}{
		{"", 4, "ListAssetsRecommendInput", false}, {"video", 1, "ListAssetsRecommendCandidateInput", false},
		{"image", 3, "ListAssetsRecommendInput", false}, {"missing", 0, "ListAssetsRecommendCandidateInput", false},
		{"image", 3, "ListAssetsRecommendCandidateInput", true},
	} {
		t.Run(fmt.Sprintf("%s/stale=%v", scenario.media, scenario.stale), func(t *testing.T) {
			trace := &recommendReadTrace{DB: conn, stale: scenario.stale}
			media := sql.NullString{String: scenario.media, Valid: scenario.media != ""}
			input, err := LoadRecommendationInputs(context.Background(), db.New(trace), "2026-01-01", media, 0)
			if err != nil {
				t.Fatal(err)
			}
			if len(input.Rows) != scenario.count {
				t.Fatalf("candidate count %d", len(input.Rows))
			}
			if !slices.Contains(trace.names, "-- name: "+scenario.plan+" :many") {
				t.Fatal("选择错误计划", trace.names)
			}
			if scenario.media == "" && len(trace.names) != 2 {
				t.Fatal("普通宽流不能增加观测查询", trace.names)
			}
			expected, err := q.ListAssetsRecommendInput(context.Background(), db.ListAssetsRecommendInputParams{Day: "2026-01-01", MediaType: media, CosOnly: int64(0)})
			if err != nil {
				t.Fatal(err)
			}
			if len(expected) != len(input.Rows) || (len(expected) > 0 && !reflect.DeepEqual(expected, input.Rows)) {
				t.Fatal("输入字段/顺序改变")
			}
			for _, row := range input.Rows {
				if !slices.Equal(input.TagsByAsset[row.AssetID], []string{"Alpha"}) {
					t.Fatal("标签改变")
				}
			}
		})
	}
}

func TestRecommendationInputEmptyDatabase(t *testing.T) {
	_, q := openTestDB(t)
	media := sql.NullString{String: "video", Valid: true}
	scope, err := q.CountRecommendScope(context.Background(), db.CountRecommendScopeParams{MediaType: media, CosOnly: int64(1)})
	if err != nil || scope.TotalAssets != 0 || scope.CandidateAssets != 0 {
		t.Fatalf("空库规模必须为整数零: %+v %v", scope, err)
	}
	input, err := LoadRecommendationInputs(context.Background(), q, "2026-01-01", media, 1)
	if err != nil || len(input.Rows) != 0 || len(input.TagsByAsset) != 0 {
		t.Fatalf("空库装载失败: %+v %v", input, err)
	}
}
