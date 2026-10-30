package store

import (
	"database/sql"
	"testing"

	"qimeng-media/server/internal/store/db"
)

func dimensionFixture(t *testing.T) (*sql.DB, *db.Queries, db.UpdateAssetImageDimensionsParams) {
	t.Helper()
	conn, q := openTestDB(t)
	lib := newLibrary(t, q)
	if _, err := conn.Exec(`INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at)
VALUES ('image',?,'picture.png','picture.png','image',42,'stored','created','updated')`, lib); err != nil {
		t.Fatal(err)
	}
	return conn, q, db.UpdateAssetImageDimensionsParams{
		AssetID: "image", LibraryID: lib, RootPath: "/media/test", RelPath: "picture.png", SizeBytes: 42, Mtime: "stored", UpdatedAt: "updated",
		Width: sql.NullInt64{Int64: 100, Valid: true}, Height: sql.NullInt64{Int64: 200, Valid: true},
	}
}

func TestImageDimensionsUpdatePreservesIdentityAndValidResult(t *testing.T) {
	_, q, params := dimensionFixture(t)
	n, err := q.UpdateAssetImageDimensions(t.Context(), params)
	if err != nil || n != 1 {
		t.Fatalf("update %d %v", n, err)
	}
	row, err := q.GetAsset(t.Context(), "image")
	if err != nil {
		t.Fatal(err)
	}
	if row.Width.Int64 != 100 || row.Height.Int64 != 200 || row.UpdatedAt != "updated" || row.Mtime != "stored" || row.RelPath != "picture.png" {
		t.Fatalf("metadata changed source identity: %+v", row)
	}
	params.Width.Int64 = 300
	if n, err := q.UpdateAssetImageDimensions(t.Context(), params); err != nil || n != 0 {
		t.Fatalf("overwrote known dimensions: %d %v", n, err)
	}
}

func TestImageDimensionsUpdateRejectsStaleSnapshotsAndLibraryChanges(t *testing.T) {
	for _, field := range []string{"size", "mtime", "updated", "path", "root", "library", "disabled"} {
		t.Run(field, func(t *testing.T) {
			conn, q, p := dimensionFixture(t)
			switch field {
			case "size":
				p.SizeBytes++
			case "mtime":
				p.Mtime = "old"
			case "updated":
				p.UpdatedAt = "old"
			case "path":
				p.RelPath = "old.png"
			case "root":
				p.RootPath = "/other"
			case "library":
				p.LibraryID = "other"
			case "disabled":
				if _, err := conn.Exec("UPDATE libraries SET enabled=0 WHERE id=?", p.LibraryID); err != nil {
					t.Fatal(err)
				}
			}
			if n, err := q.UpdateAssetImageDimensions(t.Context(), p); err != nil || n != 0 {
				t.Fatalf("stale update accepted: %d %v", n, err)
			}
		})
	}
}
