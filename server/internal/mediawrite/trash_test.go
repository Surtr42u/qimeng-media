package mediawrite

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/thumbnail"
)

// 不经 HTTP 验证文件已搬回但统计事务失败时，原身份/事件/意图仍能一致重试。
func TestRestoreBusinessKeepsIntentUntilStatisticsCommit(t *testing.T) {
	svc, library, media, body := uploadFixture(t)
	svc.thumbs = thumbnail.NewGenerator(svc.cfg.DataDir, nil, thumbnail.Options{})
	ctx := context.Background()
	asset, err := svc.Direct(ctx, DirectUpload{LibraryID: library, Filename: "restore.png", Body: bytes.NewReader(body), ContentLength: int64(len(body))})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.conn.Exec("INSERT INTO view_events(asset_id,kind,session_id,started_at,client_event_id) VALUES(?,'open','synthetic-session','2026-10-21T00:00:00.000Z','synthetic-restore-event')", asset.AssetID); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.conn.Exec("INSERT INTO favorites(asset_id,created_at) VALUES(?,'2026-10-21T00:00:00.000Z')", asset.AssetID); err != nil {
		t.Fatal(err)
	}
	if err := svc.MoveToTrash(ctx, asset.AssetID); err != nil {
		t.Fatal(err)
	}
	entries, err := svc.ListTrash()
	if err != nil || len(entries) != 1 {
		t.Fatalf("软删除未建立恢复依据: %v %v", entries, err)
	}
	entry := entries[0]
	if _, err := svc.conn.Exec("CREATE TRIGGER synthetic_restore_stats BEFORE INSERT ON asset_daily_stats BEGIN SELECT RAISE(ABORT,'synthetic stats failure'); END"); err != nil {
		t.Fatal(err)
	}
	if err := svc.RestoreTrash(ctx, entry.ID); err == nil {
		t.Fatal("统计失败应阻止整个身份重建事务")
	}
	var count int
	if err := svc.conn.QueryRow("SELECT COUNT(*) FROM assets WHERE asset_id=?", asset.AssetID).Scan(&count); err != nil || count != 0 {
		t.Fatalf("身份行未随统计失败回滚: %d %v", count, err)
	}
	entries, err = svc.ListTrash()
	if err != nil || len(entries) != 1 || entries[0].Meta.RestorePath != "restore.png" {
		t.Fatalf("恢复意图丢失: %v %v", entries, err)
	}
	if _, err := os.Stat(entry.MetaFile); err != nil {
		t.Fatal("失败不能提前删除 meta", err)
	}
	restored := filepath.Join(media, "restore.png")
	if content, err := os.ReadFile(restored); err != nil || !bytes.Equal(content, body) {
		t.Fatal("已搬回原件必须保留", err)
	}
	if _, err := svc.conn.Exec("DROP TRIGGER synthetic_restore_stats"); err != nil {
		t.Fatal(err)
	}
	if err := svc.RestoreTrash(ctx, entry.ID); err != nil {
		t.Fatal(err)
	}
	row, err := svc.q.GetAsset(ctx, asset.AssetID)
	if err != nil || row.RelPath != "restore.png" {
		t.Fatalf("重试未恢复原身份: %+v %v", row, err)
	}
	if _, err := os.Stat(entry.MetaFile); !os.IsNotExist(err) {
		t.Fatal("提交成功才删除恢复 meta")
	}
	if err := svc.conn.QueryRow("SELECT SUM(view_count) FROM asset_daily_stats WHERE asset_id=?", asset.AssetID).Scan(&count); err != nil || count != 1 {
		t.Fatalf("日统计未按保留事件重建: %d %v", count, err)
	}
	if err := svc.conn.QueryRow("SELECT COUNT(*) FROM favorites WHERE asset_id=?", asset.AssetID).Scan(&count); err != nil || count != 0 {
		t.Fatal("本次重构不得改变不恢复收藏的既定语义", err)
	}
	files, err := os.ReadDir(media)
	if err != nil || len(files) != 1 {
		t.Fatalf("恢复重试产生重复文件: %v %v", files, err)
	}
}

// TestPurgeTrashDropsAuthorTXTWorkLine：移入回收站不改 TXT（保留恢复依据），
// 物理删除前同事务清掉作品行并修剪上传条目。
func TestPurgeTrashDropsAuthorTXTWorkLine(t *testing.T) {
	svc, library, _, body := uploadFixture(t)
	svc.thumbs = thumbnail.NewGenerator(svc.cfg.DataDir, nil, thumbnail.Options{})
	ctx := context.Background()
	asset, err := svc.Direct(ctx, DirectUpload{LibraryID: library, Filename: "purged.png", Body: bytes.NewReader(body), ContentLength: int64(len(body))})
	if err != nil {
		t.Fatal(err)
	}
	content := "1  synthetic\n来源\nsite-a\n作品\npurged.png\n"
	seedSyntheticAuthorTXT(t, svc, asset.AssetID, content, "purged.png")

	if err := svc.MoveToTrash(ctx, asset.AssetID); err != nil {
		t.Fatal(err)
	}
	sources, err := authorattach.LoadSources(ctx, svc.q)
	if err != nil || len(sources) != 1 || sources[0].Content != content {
		t.Fatalf("移入回收站不得改 TXT（恢复依据）: %+v %v", sources, err)
	}
	entries, err := svc.ListTrash()
	if err != nil || len(entries) != 1 {
		t.Fatalf("回收站条目=%+v %v", entries, err)
	}
	if err := svc.PurgeTrash(entries[0].ID); err != nil {
		t.Fatalf("PurgeTrash 失败: %v", err)
	}
	sources, err = authorattach.LoadSources(ctx, svc.q)
	if err != nil || len(sources) != 1 {
		t.Fatalf("读取片段失败: %+v %v", sources, err)
	}
	if strings.Contains(sources[0].Content, "purged.png") {
		t.Fatalf("物理删除后作品行未清理: %q", sources[0].Content)
	}
	if !strings.Contains(sources[0].Content, "1  synthetic") || !strings.Contains(sources[0].Content, "site-a") {
		t.Fatalf("清理作品行不得破坏块/来源区: %q", sources[0].Content)
	}
	uploadEntries, err := authorattach.LoadUploadEntries(ctx, svc.q)
	if err != nil {
		t.Fatal(err)
	}
	if len(uploadEntries["f.txt"]) != 0 {
		t.Fatalf("物理删除后上传条目未修剪: %+v", uploadEntries)
	}
}
