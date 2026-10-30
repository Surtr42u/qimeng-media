package thumbnailwarmup

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

func imageMetadataFixture(t *testing.T) (*sql.DB, *db.Queries) {
	t.Helper()
	c, err := store.Open(filepath.Join(t.TempDir(), "metadata.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := c.Close(); err != nil {
			t.Error(err)
		}
	})
	if err := store.Migrate(c); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Exec(`INSERT INTO libraries(id,name,root_path,created_at) VALUES ('lib','synthetic','/synthetic','created');
INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at,width,height) VALUES
('a','lib','a.png','a.png','image',1,'stored','created','updated',NULL,NULL),
('b','lib','b.png','b.png','image',1,'stored','created','updated',30,40);`); err != nil {
		t.Fatal(err)
	}
	return c, db.New(c)
}

func TestImageMetadataBackfillsCachedThumbnailOnceAndNotifiesOnce(t *testing.T) {
	_, q := imageMetadataFixture(t)
	bus := events.NewBus(nil, events.DefaultBuffer)
	defer bus.Close()
	sub, err := bus.Subscribe(events.TopicLibraryChanged)
	if err != nil {
		t.Fatal(err)
	}
	defer sub.Close()
	reads := 0
	thumbs := fakeThumbs{
		has:        func(string, int64, string) bool { return true },
		submit:     func(thumbnail.Task) error { t.Fatal("cached thumbnail regenerated"); return nil },
		dimensions: func(context.Context, string, string, int64, string) (int, int, error) { reads++; return 17, 29, nil },
	}
	s := New(q, thumbs, nil, time.Time{}, bus)
	if _, _, err := s.inspect(t.Context(), false); err != nil || reads != 0 {
		t.Fatalf("progress probed original: %v %d", err, reads)
	}
	s.reconcile(t.Context())
	row, err := q.GetAsset(t.Context(), "a")
	if err != nil || row.Width.Int64 != 17 || row.Height.Int64 != 29 || reads != 1 {
		t.Fatalf("missing dimensions: %+v %d %v", row, reads, err)
	}
	select {
	case event := <-sub.C:
		if event.LibraryChange == nil || !event.LibraryChange.MetadataOnly {
			t.Fatal("metadata refresh lost its narrow invalidation scope")
		}
	default:
		t.Fatal("no refresh after committed metadata")
	}
	s.reconcile(t.Context())
	if reads != 1 {
		t.Fatal("known dimensions reopened original")
	}
	select {
	case <-sub.C:
		t.Fatal("unchanged reconciliation broadcast refresh")
	default:
	}
}

func TestMetadataCancellationWaitsForWorkerAndDoesNotPublishLate(t *testing.T) {
	_, q := imageMetadataFixture(t)
	entered, release := make(chan struct{}), make(chan struct{})
	thumbs := fakeThumbs{
		has: func(string, int64, string) bool { return true },
		dimensions: func(context.Context, string, string, int64, string) (int, int, error) {
			close(entered)
			<-release
			return 17, 29, nil
		},
	}
	s := New(q, thumbs, nil, time.Time{}, nil)
	ctx, cancel := context.WithCancel(t.Context())
	go s.Run(ctx)
	<-entered
	cancel()
	waitCtx, stopWait := context.WithCancel(t.Context())
	stopWait()
	if err := s.Wait(waitCtx); !errors.Is(err, context.Canceled) {
		t.Fatalf("returned before blocked worker: %v", err)
	}
	close(release)
	if err := s.Wait(t.Context()); err != nil {
		t.Fatal(err)
	}
	row, err := q.GetAsset(t.Context(), "a")
	if err != nil || row.Width.Valid || row.Height.Valid {
		t.Fatalf("canceled worker wrote late: %+v %v", row, err)
	}
}
