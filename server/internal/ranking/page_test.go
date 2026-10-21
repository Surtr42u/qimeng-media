package ranking

import (
	"context"
	"database/sql"
	"fmt"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/recommend"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

var rankingNow = time.Date(2026, 10, 20, 12, 0, 0, 0, time.UTC)

func seedRanking(t testing.TB, assets, events int) *sql.DB {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "ranking.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := conn.Close(); err != nil {
			t.Error(err)
		}
	})
	if err := store.Migrate(conn); err != nil {
		t.Fatal(err)
	}
	statements := []string{
		`INSERT INTO libraries(id,name,root_path,created_at) VALUES('on','synthetic','/synthetic','2026-01-01'),('off','disabled','/disabled','2026-01-01');`,
		`UPDATE libraries SET enabled=0 WHERE id='off'`,
		fmt.Sprintf(`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<%d) INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) SELECT printf('x%%06d',x),CASE WHEN x%%17=0 THEN 'off' ELSE 'on' END,x||'.jpg',x||'.jpg',CASE WHEN x%%11=0 THEN 'video' ELSE 'image' END,1024,CASE x%%4 WHEN 0 THEN 'bad' WHEN 1 THEN '2026-10-20T00:00:00.000Z' WHEN 2 THEN '2026-10-20T08:00:00.000+08:00' ELSE '' END,'2026-01-01','2026-01-01' FROM n`, assets),
		fmt.Sprintf(`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<%d) INSERT INTO view_events(asset_id,kind,seconds,session_id,started_at) SELECT printf('x%%06d',x%%%d+1),CASE x%%3 WHEN 0 THEN 'open' WHEN 1 THEN 'play' ELSE 'dwell' END,CASE WHEN x%%3=2 THEN 5 ELSE NULL END,'s'||x,CASE x%%4 WHEN 0 THEN '2026-10-20T00:00:00.000Z' WHEN 1 THEN '2026-10-01T00:00:00.000Z' WHEN 2 THEN '2026-08-01T00:00:00.000Z' ELSE '2025-01-01T00:00:00.000Z' END FROM n`, events, assets-2),
		`INSERT INTO asset_daily_stats(asset_id,day,view_count,play_count,browse_seconds) SELECT asset_id,substr(started_at,1,10),SUM(kind='open'),SUM(kind='play'),SUM(COALESCE(seconds,0)) FROM view_events GROUP BY asset_id,substr(started_at,1,10)`,
		`INSERT INTO likes(asset_id,day,created_at) SELECT asset_id,'2026-10-20','2026-10-20' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%3=0`,
		`INSERT INTO likes(asset_id,day,created_at) SELECT asset_id,'2025-01-01','2025-01-01' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%5=0`,
		`INSERT INTO favorites(asset_id,created_at) SELECT asset_id,'2026-01-01' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%7=0`,
		`INSERT INTO authors(id,display_name,type,created_at) VALUES('cos','cos','cos','2026-01-01')`,
		`INSERT INTO asset_authors(asset_id,author_id) SELECT asset_id,'cos' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%13=0`,
		`INSERT INTO view_events(asset_id,kind,session_id,started_at) VALUES('deleted','open','orphan','2026-10-20T00:00:00.000Z')`,
	}
	for _, statement := range statements {
		if _, err := conn.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	return conn
}

// The previous IO path is the oracle: full recommendation rows, separate
// window aggregates, then the already-verified pure rank selection.
func legacyPage(ctx context.Context, q *db.Queries, period string, offset, limit int, top bool) ([]db.ListRankingPageSummariesRow, error) {
	rows, err := q.ListAssetsRecommendInput(ctx, db.ListAssetsRecommendInputParams{Day: store.FormatDay(rankingNow), CosOnly: int64(0)})
	if err != nil {
		return nil, err
	}
	counts := map[string]recommend.WindowCounts{}
	if window, ok := recommend.PeriodWindow(period); ok {
		from := store.FormatDay(rankingNow.Add(-window))
		stats, err := q.SumWindowedDailyStats(ctx, from)
		if err != nil {
			return nil, err
		}
		for _, r := range stats {
			counts[r.AssetID] = recommend.WindowCounts{ViewCount: int(r.ViewCount), PlayCount: int(r.PlayCount)}
		}
		likes, err := q.CountWindowedLikes(ctx, from)
		if err != nil {
			return nil, err
		}
		for _, r := range likes {
			c := counts[r.AssetID]
			c.LikeCount = int(r.LikeCount)
			counts[r.AssetID] = c
		}
	}
	items := make([]recommend.Item, len(rows))
	byID := make(map[string]*db.ListAssetsRecommendInputRow, len(rows))
	for i, r := range rows {
		modified, parseErr := time.Parse(store.TimestampLayout, r.Mtime)
		if parseErr != nil {
			modified = time.Time{}
		}
		created, createdErr := time.Parse(store.TimestampLayout, r.CreatedAt)
		if createdErr != nil {
			created = time.Time{}
		}
		var lastViewed *time.Time
		if raw, ok := r.LastViewedAt.(string); ok {
			at, err := time.Parse(store.TimestampLayout, raw)
			if err == nil {
				lastViewed = &at
			}
		}
		items[i] = recommend.Item{AssetID: r.AssetID, FileName: r.FileName, MediaType: r.MediaType, CreatedAt: created, ModifiedAt: modified, Stats: recommend.Stats{ViewCount: int(r.ViewCount), PlayCount: int(r.PlayCount), LastViewedAt: lastViewed}, LikeCount: int(r.LikeCount), Window: counts[r.AssetID]}
		byID[r.AssetID] = &rows[i]
	}
	var ordered []recommend.Item
	if top {
		ordered = recommend.RankTop(items, period, rankingNow, offset+limit)
	} else {
		ordered = recommend.Rank(items, period, rankingNow)
	}
	result := []db.ListRankingPageSummariesRow{}
	if offset >= len(ordered) {
		return result, nil
	}
	ordered = ordered[offset:]
	if limit < len(ordered) {
		ordered = ordered[:limit]
	}
	for _, item := range ordered {
		r := byID[item.AssetID]
		result = append(result, db.ListRankingPageSummariesRow{AssetID: r.AssetID, FileName: r.FileName, MediaType: r.MediaType, SizeBytes: r.SizeBytes, Mtime: r.Mtime, CreatedAt: r.CreatedAt, Source: r.Source, IsFavorite: r.IsFavorite, LikeCount: r.LikeCount, ViewCount: r.ViewCount, PlayCount: r.PlayCount})
	}
	return result, nil
}

func TestPageMatchesLegacy(t *testing.T) {
	q := db.New(seedRanking(t, 96, 300))
	for _, period := range []string{"all", "day", "week", "month", "quarter", "year", "unknown"} {
		for _, offset := range []int{0, 1, 40, 95, int(^uint(0) >> 1)} {
			for _, limit := range []int{1, 13, 200} {
				want, err := legacyPage(context.Background(), q, period, offset, limit, false)
				if err != nil {
					t.Fatal(err)
				}
				got, _, err := LoadPage(context.Background(), q, period, rankingNow, offset, limit)
				if err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(want, got) {
					t.Fatalf("period=%s offset=%d limit=%d\nwant=%+v\ngot=%+v", period, offset, limit, want, got)
				}
			}
		}
	}
}

type beforeSummaryDB struct {
	*sql.DB
	mutate func()
}

func (c beforeSummaryDB) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	if strings.HasPrefix(query, "-- name: ListRankingPageSummaries") {
		c.mutate()
	}
	return c.DB.QueryContext(ctx, query, args...)
}

func TestPageRechecksEligibilityBetweenReads(t *testing.T) {
	for _, mutation := range []string{"DELETE FROM assets", "UPDATE libraries SET enabled=0", "INSERT OR IGNORE INTO asset_authors(asset_id,author_id) SELECT asset_id,'cos' FROM assets"} {
		t.Run(mutation, func(t *testing.T) {
			conn := seedRanking(t, 20, 60)
			q := db.New(beforeSummaryDB{conn, func() {
				if _, err := conn.Exec(mutation); err != nil {
					t.Fatal(err)
				}
			}})
			got, _, err := LoadPage(context.Background(), q, "all", rankingNow, 0, 200)
			if err != nil || len(got) != 0 {
				t.Fatalf("ineligible page: %+v %v", got, err)
			}
		})
	}
}

func TestShortRankingPagePreservesRawNextOffset(t *testing.T) {
	conn := seedRanking(t, 20, 60)
	rows, _, err := LoadPage(context.Background(), db.New(conn), "all", rankingNow, 0, 2)
	if err != nil || len(rows) != 2 {
		t.Fatalf("fixture page: %v %v", rows, err)
	}
	q := db.New(beforeSummaryDB{conn, func() {
		if _, err := conn.Exec("DELETE FROM assets WHERE asset_id IN (?,?)", rows[0].AssetID, rows[1].AssetID); err != nil {
			t.Fatal(err)
		}
	}})
	page, next, err := LoadPage(context.Background(), q, "all", rankingNow, 0, 2)
	if err != nil || len(page) != 0 || next == nil || *next != 2 {
		t.Fatalf("short intermediate page must continue: rows=%d next=%v err=%v", len(page), next, err)
	}
}

func TestPageErrorsAndEmpty(t *testing.T) {
	conn := seedRanking(t, 20, 60)
	q := db.New(conn)
	for _, p := range [][2]int{{-1, 1}, {0, 0}, {0, -1}} {
		if _, _, err := LoadPage(context.Background(), q, "all", rankingNow, p[0], p[1]); err == nil {
			t.Fatal("invalid page accepted")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, _, err := LoadPage(ctx, q, "all", rankingNow, 0, 10); err == nil {
		t.Fatal("cancel ignored")
	}
	got, _, err := LoadPage(context.Background(), q, "day", rankingNow.AddDate(10, 0, 0), 0, 10)
	if err != nil || len(got) != 0 {
		t.Fatalf("empty window: %v %v", got, err)
	}
	for _, test := range []struct{ period, query string }{{"all", "ListRankingAllInput"}, {"week", "ListRankingWindowInput"}, {"week", "ListRankingPageSummaries"}} {
		failed := db.New(failingDB{conn, test.query})
		if _, _, err := LoadPage(context.Background(), failed, test.period, rankingNow, 0, 10); err == nil {
			t.Fatal("query error swallowed", test.query)
		}
	}
}

type failingDB struct {
	*sql.DB
	query string
}

func (f failingDB) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	if strings.HasPrefix(query, "-- name: "+f.query) {
		return nil, fmt.Errorf("synthetic query failure: %s", f.query)
	}
	return f.DB.QueryContext(ctx, query, args...)
}

func BenchmarkPage(b *testing.B) {
	q := db.New(seedRanking(b, 20000, 400000))
	for _, period := range []string{"all", "week", "month"} {
		for _, baseline := range []bool{true, false} {
			b.Run(fmt.Sprintf("%s/baseline=%t", period, baseline), func(b *testing.B) {
				b.ReportAllocs()
				for b.Loop() {
					var rows []db.ListRankingPageSummariesRow
					var err error
					if baseline {
						rows, err = legacyPage(context.Background(), q, period, 0, 200, true)
					} else {
						rows, _, err = LoadPage(context.Background(), q, period, rankingNow, 0, 200)
					}
					if err != nil || len(rows) != 200 {
						b.Fatalf("rows=%d err=%v", len(rows), err)
					}
				}
			})
		}
	}
}
