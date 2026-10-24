package store

import (
	"context"
	"fmt"
	"testing"
	"time"

	"qimeng-media/server/internal/store/db"
)

func TestThumbnailWarmupKeysetEnabledScopeAndProjection(t *testing.T) {
	conn, q := openTestDB(t)
	ctx := context.Background()
	enabled := newLibrary(t, q)
	disabled := "disabled-library"
	if _, err := q.CreateLibrary(ctx, db.CreateLibraryParams{ID: disabled, Name: "disabled", RootPath: "/media/disabled", Kind: "normal", CreatedAt: FormatTimestamp(time.Now())}); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.ExecContext(ctx, "UPDATE libraries SET enabled=0 WHERE id=?", disabled); err != nil {
		t.Fatal(err)
	}
	for i, lib := range []string{enabled, enabled, disabled, enabled, enabled} {
		if _, err := conn.ExecContext(ctx, `INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)`,
			fmt.Sprintf("asset-%d", i), lib, fmt.Sprintf("%d.jpg", i), fmt.Sprintf("%d.jpg", i), "image", 42, "mtime", "created", "updated"); err != nil {
			t.Fatal(err)
		}
	}
	var ids []string
	after := ""
	for {
		rows, err := q.ListThumbnailWarmupPage(ctx, db.ListThumbnailWarmupPageParams{AfterID: after, PageSize: 2})
		if err != nil {
			t.Fatal(err)
		}
		if len(rows) > 2 {
			t.Fatal("页大小失效")
		}
		if len(rows) == 0 {
			break
		}
		for _, r := range rows {
			if r.SizeBytes != 42 || r.Mtime != "mtime" || r.RootPath != "/media/test" {
				t.Fatalf("元数据投影错误: %+v", r)
			}
			ids = append(ids, r.AssetID)
		}
		after = rows[len(rows)-1].AssetID
	}
	if fmt.Sprint(ids) != "[asset-0 asset-1 asset-3 asset-4]" {
		t.Fatalf("分页遗漏/重复/启用范围错误: %v", ids)
	}
}
