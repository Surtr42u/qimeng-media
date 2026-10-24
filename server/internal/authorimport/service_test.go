package authorimport

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func TestImportProtectionAndRebuildRollbackWithoutHTTP(t *testing.T) {
	ctx := context.Background()
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
	q := db.New(conn)
	now := store.FormatTimestamp(time.Now())
	lib, err := q.CreateLibrary(ctx, db.CreateLibraryParams{ID: uuid.NewString(), Name: "synthetic", RootPath: t.TempDir(), Kind: "normal", CreatedAt: now})
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"a.png", "b.png"} {
		if _, err := q.UpsertAsset(ctx, db.UpsertAssetParams{AssetID: uuid.NewString(), LibraryID: lib.ID, RelPath: name, FileName: name, MediaType: "image", SizeBytes: 1, Mtime: now, CreatedAt: now, UpdatedAt: now}); err != nil {
			t.Fatal(err)
		}
	}
	s := New(conn, q, nil, nil)
	filename := "authors.txt"
	full := "1  作者甲\n作品\na.png\nb.png\n"
	stale := "1  作者甲\n作品\na.png\n"
	result, err := s.Import(ctx, &filename, full, "", store.OriginTXT)
	if err != nil || result.AuthorsImported != 1 || result.FilesMatched != 2 {
		t.Fatalf("导入计数: %+v %v", result, err)
	}
	id := authoring.GenerateAuthorID("作者甲")
	if err := authorattach.SaveUploadEntries(ctx, q, time.Now(), map[string][]authoring.UploadEntry{filename: {{AuthorID: id, DisplayName: "作者甲", Works: []string{"b.png"}}}}); err != nil {
		t.Fatal(err)
	}
	_, err = s.Import(ctx, &filename, stale, "", store.OriginTXT)
	var conflict *Conflict
	if !errors.As(err, &conflict) || len(conflict.Missing) != 1 {
		t.Fatalf("应保护已登记作品: %v", err)
	}
	sources, err := authorattach.LoadSources(ctx, q)
	if err != nil || len(sources) != 1 || sources[0].Content != full {
		t.Fatal("冲突不得覆盖原片段", err)
	}
	result, err = s.Import(ctx, &filename, stale, Keep, store.OriginLocalSync)
	if err != nil || result.MergedUploadEntries == nil || *result.MergedUploadEntries != 1 {
		t.Fatalf("keep 合并: %+v %v", result, err)
	}
	sources, err = authorattach.LoadSources(ctx, q)
	if err != nil || !strings.Contains(sources[0].Content, "b.png") {
		t.Fatal("keep 丢登记条目", err)
	}
	if _, err := conn.Exec(`CREATE TRIGGER fail_rebuild BEFORE INSERT ON asset_authors BEGIN SELECT RAISE(ABORT,'synthetic failure'); END`); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Rebuild(ctx); err == nil {
		t.Fatal("未注入重建故障")
	}
	var count int
	if err := conn.QueryRow(`SELECT COUNT(*) FROM asset_authors`).Scan(&count); err != nil || count != 2 {
		t.Fatalf("失败重建不得清空关联: %d %v", count, err)
	}
	// 移除的重建路径故障同样不能提交片段删除；此处用片段写入故障阻断。
	if _, err := conn.Exec(`CREATE TRIGGER fail_remove BEFORE UPDATE ON kv_settings BEGIN SELECT RAISE(ABORT,'synthetic failure'); END`); err != nil {
		t.Fatal(err)
	}
	if err := s.Remove(ctx, filename); err == nil {
		t.Fatal("未注入片段删除故障")
	}
	sources, err = authorattach.LoadSources(ctx, q)
	if err != nil || len(sources) != 1 {
		t.Fatal("失败移除必须保留片段", err)
	}
	if _, err := conn.Exec(`DROP TRIGGER fail_rebuild`); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Exec(`DROP TRIGGER fail_remove`); err != nil {
		t.Fatal(err)
	}
	result, err = s.Rebuild(ctx)
	if err != nil || !result.Rebuilt || result.FilesMatched != 2 {
		t.Fatalf("重试重建失败: %+v %v", result, err)
	}
	if err := s.Remove(ctx, filename); err != nil {
		t.Fatal(err)
	}
	entries, err := authorattach.LoadUploadEntries(ctx, q)
	if err != nil || len(entries) != 0 {
		t.Fatal("移除必须清登记元数据", err)
	}
	if err := conn.QueryRow(`SELECT COUNT(*) FROM assets`).Scan(&count); err != nil || count != 2 {
		t.Fatal("移除作者片段不能删媒体资产", err)
	}
	if err := conn.QueryRow(`SELECT COUNT(*) FROM asset_authors`).Scan(&count); err != nil || count != 0 {
		t.Fatal("移除片段应解除相关关联", err)
	}
}
