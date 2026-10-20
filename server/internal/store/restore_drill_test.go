package store

import (
	"context"
	"crypto/sha256"
	"os"
	"path/filepath"
	"testing"
)

// 真迁移库→在线快照→新数据目录恢复→重新打开与核对；原件必须独立复制。
// 不接触运行实例/真实媒体，不将此同机演练描述成 NAS/掉电验收。
func TestSnapshotRestoreIntoFreshDataDirectory(t *testing.T) {
	conn, q := openTestDB(t)
	lib := newLibrary(t, q)
	ctx := context.Background()
	statements := []string{
		`INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) VALUES('restore-asset',?,'example.jpg','example.jpg','image',15,'2026-01-01','2026-01-01','2026-01-01')`,
		`INSERT INTO view_events(asset_id,kind,session_id,started_at,client_event_id) VALUES('restore-asset','open','synthetic-session','2026-01-01T00:00:00.000Z','synthetic-event')`,
		`INSERT INTO favorites(asset_id,created_at) VALUES('restore-asset','2026-01-01')`,
	}
	for i, statement := range statements {
		var err error
		if i == 0 {
			_, err = conn.ExecContext(ctx, statement, lib)
		} else {
			_, err = conn.ExecContext(ctx, statement)
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	media := []byte("synthetic-media")
	original := filepath.Join(t.TempDir(), "example.jpg")
	if err := os.WriteFile(original, media, 0o600); err != nil {
		t.Fatal(err)
	}
	snapshot := filepath.Join(t.TempDir(), "snapshot.db")
	if err := VacuumInto(conn, snapshot); err != nil {
		t.Fatal(err)
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
	newData := t.TempDir()
	bytes, err := os.ReadFile(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	restoredFile := filepath.Join(newData, "restored.db")
	if err := os.WriteFile(restoredFile, bytes, 0o600); err != nil {
		t.Fatal(err)
	}
	restored, err := Open(restoredFile)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := restored.Close(); err != nil {
			t.Error(err)
		}
	})
	if err := Migrate(restored); err != nil {
		t.Fatal(err)
	}
	var eventID string
	if err := restored.QueryRow(`SELECT client_event_id FROM view_events WHERE asset_id='restore-asset'`).Scan(&eventID); err != nil || eventID != "synthetic-event" {
		t.Fatalf("事件身份未恢复: %q %v", eventID, err)
	}
	var count int
	if err := restored.QueryRow(`SELECT COUNT(*) FROM favorites WHERE asset_id='restore-asset'`).Scan(&count); err != nil || count != 1 {
		t.Fatal("收藏未恢复", err)
	}
	newMedia := t.TempDir()
	copyPath := filepath.Join(newMedia, "example.jpg")
	if _, err := os.Stat(copyPath); !os.IsNotExist(err) {
		t.Fatal("数据库快照不应包含原件")
	}
	data, err := os.ReadFile(original)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(copyPath, data, 0o600); err != nil {
		t.Fatal(err)
	}
	copyBytes, err := os.ReadFile(copyPath)
	if err != nil || sha256.Sum256(copyBytes) != sha256.Sum256(media) {
		t.Fatal("独立原件副本不一致", err)
	}
}
