package statsread

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func TestReviewWindowsPartitionsAndReadOnly(t *testing.T) {
	t.Setenv("TZ", "UTC")
	previousZone := time.Local
	time.Local = time.UTC
	t.Cleanup(func() { time.Local = previousZone })
	conn, err := store.Open(filepath.Join(t.TempDir(), "review.db"))
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
	conn.SetMaxOpenConns(1)
	statements := []string{
		`INSERT INTO libraries(id,name,root_path,created_at) VALUES('on','synthetic','/synthetic','2026-01-01'),('off','off','/off','2026-01-01')`,
		`UPDATE libraries SET enabled=0 WHERE id='off'`,
		`INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) VALUES('old','on','old.jpg','old.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01'),('new','on','new.jpg','new.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01'),('off','off','off.mp4','off.mp4','video',1,'2026-01-01','2026-01-01','2026-01-01')`,
		`INSERT INTO authors(id,display_name,type,created_at) VALUES('old-author','Old','normal','2026-01-01'),('cos_new','New','cos','2026-01-01')`,
		`INSERT INTO asset_authors(asset_id,author_id) VALUES('old','old-author'),('new','cos_new')`,
		`INSERT INTO tags(id,name,created_at) VALUES('tag','Landscape','2026-01-01')`,
		`INSERT INTO asset_tags(asset_id,tag_id) VALUES('new','tag')`,
	}
	for _, statement := range statements {
		if _, err := conn.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	activities := []struct {
		asset, day string
		views      int
	}{{"old", "2026-10-01", 1}, {"old", "2026-10-16", 4}, {"old", "2026-10-22", 3}, {"new", "2026-10-23", 2}, {"off", "2026-10-24", 1}}
	for _, activity := range activities {
		if _, err := conn.Exec(`INSERT INTO asset_daily_stats(asset_id,day,view_count,play_count,browse_seconds) VALUES(?,?,?,0,0)`, activity.asset, activity.day, activity.views); err != nil {
			t.Fatal(err)
		}
		for i := 0; i < activity.views; i++ {
			if _, err := conn.Exec(`INSERT INTO view_events(asset_id,kind,session_id,started_at) VALUES(?,'open',?,?)`, activity.asset, fmt.Sprintf("%s-%d", activity.day, i), activity.day+"T12:00:00.000Z"); err != nil {
				t.Fatal(err)
			}
		}
	}
	// Orphan history is retained in the event stream but excluded from this report.
	if _, err := conn.Exec(`INSERT INTO view_events(asset_id,kind,session_id,started_at) VALUES('deleted','open','orphan','2026-10-24T12:00:00.000Z'); PRAGMA query_only=ON`); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	now := time.Date(2026, 10, 28, 12, 0, 0, 0, time.UTC)
	result, err := LoadReview(context.Background(), q, stats.Range7d, now)
	if err != nil {
		t.Fatal(err)
	}
	d := result.Data
	if result.FromDay != "2026-10-22" || result.PreviousFromDay != "2026-10-15" || result.PreviousToDay != "2026-10-21" {
		t.Fatal(result)
	}
	if d.Views != 6 || d.Files != 3 || d.FirstViews != 2 || d.RepeatViews != 4 || d.ActiveDays != 3 || d.PreviousViews != 4 {
		t.Fatal(d)
	}
	if len(d.Interests) != 3 || d.Interests[0].ID != "old-author" || d.Interests[0].ChangePoints != -50 || d.Hours[12].Views != 6 {
		t.Fatal(d)
	}
	all, err := LoadReview(context.Background(), q, stats.RangeAll, now)
	if err != nil {
		t.Fatal(err)
	}
	if all.Data.Views != 11 || all.Data.FirstViews != 3 || all.Data.RepeatViews != 8 || all.Data.ComparisonAvailable || len(all.Data.Interests) != 0 {
		t.Fatal(all)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := LoadReview(ctx, q, stats.Range7d, now); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
