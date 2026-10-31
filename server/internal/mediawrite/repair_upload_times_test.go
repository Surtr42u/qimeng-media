package mediawrite

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"qimeng-media/server/internal/store"
)

func TestUploadTimeRepairScopedPreviewAndApply(t *testing.T) {
	base := t.TempDir()
	conn, err := store.Open(filepath.Join(base, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err = store.Migrate(conn); err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(base, "media")
	archive := filepath.Join(base, "archive")
	if err = os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	if err = os.MkdirAll(filepath.Join(archive, "images"), 0o755); err != nil {
		t.Fatal(err)
	}
	old := time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)
	added := time.Date(2026, 10, 30, 14, 0, 0, 0, time.UTC)
	if _, err = conn.Exec(`INSERT INTO libraries(id,name,root_path,created_at,kind,enabled) VALUES('lib','images',?,'2026-01-01','normal',1)`, root); err != nil {
		t.Fatal(err)
	}
	for _, item := range []struct{ name, created string }{{"Liepraag-new.jpg", store.FormatTimestamp(added)}, {"Liepraag-old.jpg", "2026-10-29T00:00:00.000Z"}, {"other.jpg", store.FormatTimestamp(added)}} {
		file := filepath.Join(root, item.name)
		if err = os.WriteFile(file, []byte("synthetic"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err = os.Chtimes(file, old, old); err != nil {
			t.Fatal(err)
		}
		if _, err = conn.Exec(`INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) VALUES(?,'lib',?,?,'image',9,?,?,?)`, item.name, item.name, item.name, store.FormatTimestamp(old), item.created, item.created); err != nil {
			t.Fatal(err)
		}
	}
	archived := filepath.Join(archive, "images", "original-download-name.jpg")
	different := filepath.Join(archive, "images", "different.jpg")
	for path, data := range map[string]string{archived: "synthetic", different: "different"} {
		if err = os.WriteFile(path, []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
		if err = os.Chtimes(path, old, old); err != nil {
			t.Fatal(err)
		}
	}
	request := filepath.Join(base, "request.json")
	req := UploadTimeRepairRequest{From: "2026-10-30T00:00:00+08:00", To: "2026-10-31T00:00:00+08:00", Keyword: "Liepraag", ExpectedCount: 1}
	write := func() {
		t.Helper()
		b, e := json.Marshal(req)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(request, b, 0o600); e != nil {
			t.Fatal(e)
		}
	}
	assertTime := func(path string, want time.Time) {
		t.Helper()
		info, e := os.Stat(path)
		if e != nil {
			t.Fatal(e)
		}
		if !info.ModTime().Equal(want) {
			t.Fatalf("timestamp mismatch: %s", filepath.Base(path))
		}
	}
	write()
	if err = RepairUploadTimes(context.Background(), conn, request, archive); err != nil {
		t.Fatal(err)
	}
	assertTime(filepath.Join(root, "Liepraag-new.jpg"), old)
	req.Apply = true
	req.ExpectedCount = 2
	write()
	if err = RepairUploadTimes(context.Background(), conn, request, archive); err == nil {
		t.Fatal("count mismatch must reject")
	}
	assertTime(filepath.Join(root, "Liepraag-new.jpg"), old)
	req.ExpectedCount = 1
	write()
	if err = RepairUploadTimes(context.Background(), conn, request, archive); err != nil {
		t.Fatal(err)
	}
	assertTime(filepath.Join(root, "Liepraag-new.jpg"), added)
	assertTime(archived, added)
	assertTime(different, old)
	assertTime(filepath.Join(root, "Liepraag-old.jpg"), old)
	assertTime(filepath.Join(root, "other.jpg"), old)
	// 已改文件重入不丢身份，也不改日期窗外文件。
	write()
	if err = RepairUploadTimes(context.Background(), conn, request, archive); err != nil {
		t.Fatal(err)
	}
	req.From = "2026-01-01T00:00:00Z"
	write()
	if err = RepairUploadTimes(context.Background(), conn, request, archive); err == nil {
		t.Fatal("unbounded request must reject")
	}
}
