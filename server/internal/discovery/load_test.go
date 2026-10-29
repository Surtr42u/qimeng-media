package discovery

import (
	"context"
	"path/filepath"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"testing"
	"time"
)

func TestLoadIncludesBothPartitionsAndPreservesData(t *testing.T) {
	conn, err := store.Open(filepath.Join(t.TempDir(), "discovery.db"))
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
		`INSERT INTO libraries(id,name,root_path,created_at) VALUES('on','synthetic','/synthetic','2026-01-01'),('off','disabled','/disabled','2026-01-01')`,
		`UPDATE libraries SET enabled=0 WHERE id='off'`,
		`INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) VALUES('unseen','on','1.jpg','1.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01'),('old','on','2.jpg','2.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01'),('recent','on','3.jpg','3.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01'),('hidden','off','4.jpg','4.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01')`,
		`INSERT INTO authors(id,display_name,type,created_at) VALUES('cos_synthetic','synthetic','cos','2026-01-01')`,
		`INSERT INTO asset_authors(asset_id,author_id) VALUES('unseen','cos_synthetic')`,
		`INSERT INTO view_events(asset_id,kind,session_id,started_at) VALUES('old','open','a','2026-01-01T00:00:00.000Z'),('recent','play','b','2026-10-28T00:00:00.000Z')`,
	}
	for _, statement := range statements {
		if _, err := conn.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	now := time.Date(2026, 10, 28, 12, 0, 0, 0, time.UTC)
	entries, err := Load(context.Background(), db.New(conn), 42, 6, now)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 3 {
		t.Fatal(entries)
	}
	expected := map[string]string{"unseen": Unseen, "old": Rediscover, "recent": Interest}
	for _, entry := range entries {
		if expected[entry.Asset.AssetID] != entry.Reason {
			t.Fatal(entry)
		}
	}
	var events, shown int
	if err := conn.QueryRow(`SELECT COUNT(*) FROM view_events`).Scan(&events); err != nil {
		t.Fatal(err)
	}
	if err := conn.QueryRow(`SELECT COUNT(*) FROM daily_shown`).Scan(&shown); err != nil {
		t.Fatal(err)
	}
	if events != 2 || shown != 0 {
		t.Fatalf("unexpected writes: events=%d shown=%d", events, shown)
	}
}
