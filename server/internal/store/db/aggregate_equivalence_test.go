package db

import (
	"context"
	"database/sql"
	"encoding/json"
	"reflect"
	"slices"
	"strings"
	"testing"
)

// Baseline substitution exists only in tests: keep generated Go row decoding
// while executing the pre-optimization SQL oracle. No production SQL rewriting.
type recommendBaselineDB struct{ *sql.DB }

func (b recommendBaselineDB) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	switch {
	case strings.HasPrefix(query, "-- name: ListAssetsRecommendCandidateInput :many"),
		strings.HasPrefix(query, "-- name: ListAssetsRecommendInput :many"):
		query = recommendInputBaseline
	case strings.HasPrefix(query, "-- name: ListTagsForAssets :many"):
		query, args = recommendTagsBaseline, nil
	}
	return b.DB.QueryContext(ctx, query, args...)
}

func seedAggregateEquivalence(t *testing.T) *sql.DB {
	t.Helper()
	conn := seedFileDateEquivalence(t)
	statements := []string{
		`INSERT INTO tags(id,name,created_at) VALUES('a','Alpha','2026-01-01'),('z','Zulu','2026-01-01'),('c','测试','2026-01-01'),('unused','Unused','2026-01-01')`,
		`INSERT INTO asset_tags(asset_id,tag_id,created_at) SELECT a.asset_id,t.id,'2026-01-01' FROM assets a CROSS JOIN tags t WHERE t.id!='unused' AND CAST(substr(a.asset_id,2) AS INTEGER)%4!=0`,
		`UPDATE assets SET media_type='video' WHERE CAST(substr(asset_id,2) AS INTEGER)%7=0`,
		`UPDATE assets SET media_type='animated_image' WHERE CAST(substr(asset_id,2) AS INTEGER)%11=0`,
		`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<160) INSERT INTO view_events(asset_id,kind,session_id,seconds,started_at) SELECT printf('x%03d',x%90+1),CASE x%3 WHEN 0 THEN 'open' WHEN 1 THEN 'play' ELSE 'dwell' END,'s'||x,CASE WHEN x%3=2 THEN 7 ELSE NULL END,CASE WHEN x%2=0 THEN '2026-01-02T00:00:00.000Z' ELSE '2026-01-01T23:59:59.999Z' END FROM n`,
		`INSERT INTO view_events(asset_id,kind,session_id,started_at) VALUES('deleted','open','orphan','2026-01-02T00:00:00.000Z')`,
		`INSERT INTO daily_shown(asset_id,day,count) VALUES('x003','2026-01-01',3),('x007','2026-01-02',5)`,
	}
	for _, statement := range statements {
		if _, err := conn.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	return conn
}

func readAggregateSQL(t *testing.T, conn *sql.DB, query string, args ...any) [][]any {
	t.Helper()
	rows, err := conn.Query(query, args...)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	columns, err := rows.Columns()
	if err != nil {
		t.Fatal(err)
	}
	result := make([][]any, 0)
	for rows.Next() {
		values := make([]any, len(columns))
		pointers := make([]any, len(columns))
		for i := range values {
			pointers[i] = &values[i]
		}
		if err := rows.Scan(pointers...); err != nil {
			t.Fatal(err)
		}
		result = append(result, values)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return result
}

func TestHotAggregatesMatchEventJoinBaseline(t *testing.T) {
	conn := seedAggregateEquivalence(t)
	for _, family := range []struct{ name, current, baseline string }{{"authors", topOpenAuthors, topAuthorsBaseline}, {"tags", topOpenTags, topTagsBaseline}} {
		for _, from := range []string{"", "2026-01-02T00:00:00.000Z", "2026-01-03T00:00:00.000Z"} {
			for _, limit := range []int64{1, 2, 20} {
				old := readAggregateSQL(t, conn, family.baseline, from, limit)
				actual := readAggregateSQL(t, conn, family.current, limit, from)
				if !reflect.DeepEqual(old, actual) {
					t.Fatalf("%s from=%s limit=%d actual=%v expected=%v", family.name, from, limit, actual, old)
				}
			}
		}
	}
}

func TestRecommendCandidateInputsMatchBaseline(t *testing.T) {
	conn := seedAggregateEquivalence(t)
	q := New(conn)
	old := New(recommendBaselineDB{conn})
	for _, media := range []any{nil, "image", "video", "animated_image", "missing"} {
		for _, cos := range []int64{0, 1} {
			for _, day := range []string{"2026-01-01", "2026-01-02"} {
				p := ListAssetsRecommendInputParams{Day: day, MediaType: media, CosOnly: cos}
				expected, err := old.ListAssetsRecommendInput(context.Background(), p)
				if err != nil {
					t.Fatal(err)
				}
				raw, err := q.ListAssetsRecommendCandidateInput(context.Background(), ListAssetsRecommendCandidateInputParams(p))
				actual := make([]ListAssetsRecommendInputRow, len(raw))
				for i, row := range raw {
					actual[i] = ListAssetsRecommendInputRow(row)
				}
				if err != nil {
					t.Fatal(err)
				}
				if len(expected) != len(actual) || (len(actual) > 0 && !reflect.DeepEqual(expected, actual)) {
					t.Fatalf("候选字段/顺序不等: media=%v cos=%d day=%s", media, cos, day)
				}
				scope, err := q.CountRecommendScope(context.Background(), CountRecommendScopeParams{MediaType: media, CosOnly: cos})
				if err != nil || scope.TotalAssets != 96 || scope.CandidateAssets != int64(len(expected)) {
					t.Fatalf("候选规模口径不等: media=%v cos=%d scope=%+v expected=%d err=%v", media, cos, scope, len(expected), err)
				}
				ids := make([]string, len(actual))
				set := map[string]bool{}
				for i, row := range actual {
					ids[i] = row.AssetID
					set[row.AssetID] = true
				}
				payload, err := json.Marshal(ids)
				if err != nil {
					t.Fatal(err)
				}
				tags, err := q.ListTagsForAssets(context.Background(), string(payload))
				if err != nil {
					t.Fatal(err)
				}
				all, err := old.ListTagsForAssets(context.Background(), nil)
				if err != nil {
					t.Fatal(err)
				}
				var expectedTags []ListTagsForAssetsRow
				for _, tag := range all {
					if set[tag.AssetID] {
						expectedTags = append(expectedTags, tag)
					}
				}
				if !reflect.DeepEqual(tags, expectedTags) {
					t.Fatalf("标签内容/顺序不同: media=%v cos=%d", media, cos)
				}
			}
		}
	}
	for _, empty := range []any{nil, "[]"} {
		tags, err := q.ListTagsForAssets(context.Background(), empty)
		if err != nil || len(tags) != 0 {
			t.Fatalf("空候选不能读全库标签: %v %v", tags, err)
		}
	}
}

func TestCandidateAggregatePlansBoundEventRead(t *testing.T) {
	conn := openMigratedDB(t)
	for _, query := range []string{topOpenAuthors, topOpenTags} {
		plan := explainPlan(t, conn, query, int64(20), "")
		joined := strings.Join(plan, "\n")
		if !strings.Contains(joined, "asset_opens") {
			t.Fatalf("缺少按资产的预聚合: %v", plan)
		}
		assertPlanShape(t, plan, []string{"SEARCH v USING", "asset_id=?"}, []string{"SCAN v USING"})
		if slices.Contains(plan, "SCAN v") {
			t.Fatal("原始事件全表扫描", plan)
		}
		t.Logf("hot plan: %v", plan)
	}
	plan := explainPlan(t, conn, listAssetsRecommendCandidateInput, "2026-01-01", "video", int64(0))
	// SQLite may inline candidates; assert bounded event access rather than CTE names.
	assertPlanShape(t, plan, []string{"LIST SUBQUERY", "SEARCH v USING", "asset_id=?"}, []string{"SCAN v USING"})
	if slices.Contains(plan, "SCAN v") {
		t.Fatal("原始事件全表扫描", plan)
	}
	t.Logf("recommend candidate plan: %v", plan)
}
