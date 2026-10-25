package thumbnailwarmup

import (
	"path/filepath"
	"testing"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func TestScopedQueriesPreserveEnabledEligibilityAndKeyset(t *testing.T) {
	conn, err := store.Open(filepath.Join(t.TempDir(), "synthetic.db"))
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
	_, err = conn.Exec(`INSERT INTO libraries(id,name,root_path,created_at,enabled) VALUES
        ('a','synthetic-a','/synthetic/a','2026-01-01',1),
        ('b','synthetic-b','/synthetic/b','2026-01-01',1),
        ('disabled','synthetic-disabled','/synthetic/disabled','2026-01-01',0);
        INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) VALUES
        ('a1','a','a1.jpg','a1.jpg','image',1,'stored','2026-01-01','2026-01-01'),
        ('a2','a','a2.jpg','a2.jpg','image',2,'stored','2026-01-01','2026-01-01'),
        ('b1','b','b1.jpg','b1.jpg','image',3,'stored','2026-01-01','2026-01-01'),
        ('disabled1','disabled','d.jpg','d.jpg','image',4,'stored','2026-01-01','2026-01-01');`)
	if err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	rows, err := q.ListThumbnailWarmupAssetsPage(t.Context(), db.ListThumbnailWarmupAssetsPageParams{AssetIdsJson: `["a1","b1","disabled1","missing","a1"]`, PageSize: pageSize})
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 || rows[0].AssetID != "a1" || rows[1].AssetID != "b1" {
		t.Fatal("targeted query lost eligibility/order/uniqueness")
	}
	library, err := q.ListThumbnailWarmupLibraryPage(t.Context(), db.ListThumbnailWarmupLibraryPageParams{LibraryID: "a", AfterID: "a1", PageSize: 1})
	if err != nil || len(library) != 1 || library[0].AssetID != "a2" {
		t.Fatalf("library keyset failed: %v", err)
	}
	disabled, err := q.ListThumbnailWarmupLibraryPage(t.Context(), db.ListThumbnailWarmupLibraryPageParams{LibraryID: "disabled", PageSize: pageSize})
	if err != nil || len(disabled) != 0 {
		t.Fatalf("disabled library leaked: %v", err)
	}
}
