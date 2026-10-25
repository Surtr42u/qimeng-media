package scanner

import (
	"qimeng-media/server/internal/store/db"
	"testing"
)

func TestNarrowSnapshotMatchesFullRows(t *testing.T) {
	e := newTestEnv(t, nil)
	e.writeFile(t, "a.jpg", 10)
	e.writeFile(t, "dir/b.jpg", 20)
	if _, err := e.s.Scan(t.Context(), e.lib); err != nil {
		t.Fatal(err)
	}
	full, err := e.q.ListAssetsByLibrary(t.Context(), e.lib.ID)
	if err != nil {
		t.Fatal(err)
	}
	narrow, err := e.q.ListScanAssetSnapshots(t.Context(), e.lib.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(full) != len(narrow) {
		t.Fatal("snapshot truncated")
	}
	index := indexScanAssets(narrow)
	for i, a := range full {
		want := db.ListScanAssetSnapshotsRow{AssetID: a.AssetID, RelPath: a.RelPath, SizeBytes: a.SizeBytes, Mtime: a.Mtime, UpdatedAt: a.UpdatedAt}
		if narrow[i] != want || narrow[index[a.RelPath]] != want {
			t.Fatal("narrow snapshot differs from full query fields/order")
		}
	}
	if _, err := e.s.Scan(t.Context(), e.lib); err != nil {
		t.Fatal(err)
	}
	for _, a := range full {
		if got := e.assetByPath(t, a.RelPath); got.AssetID != a.AssetID {
			t.Fatal("empty scan changed identity")
		}
	}
}
