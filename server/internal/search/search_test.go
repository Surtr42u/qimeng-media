package search

import (
	"context"
	"reflect"
	"testing"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func TestParseQuery(t *testing.T) {
	cases := []struct {
		in   string
		want []string
	}{
		{"", nil},
		{"   ", nil},
		{"露西", []string{"露西"}},
		{"露西 赛博朋克", []string{"露西", "赛博朋克"}},
		{"  a   b\tc  ", []string{"a", "b", "c"}},
		{"DVA", []string{"DVA"}},
	}
	for _, c := range cases {
		if got := ParseQuery(c.in); !reflect.DeepEqual(got, c.want) {
			t.Errorf("ParseQuery(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestRebuildIndex(t *testing.T) {
	conn, err := store.Open(t.TempDir() + "/rebuild.db")
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	q := db.New(conn)
	ctx := context.Background()
	if _, err := q.CreateLibrary(ctx, db.CreateLibraryParams{
		ID: "lib1", Name: "库", RootPath: "/media", Kind: "normal",
		CreatedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	if _, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", LibraryID: "lib1",
		RelPath: "x/标题文件.jpg", FileName: "标题文件.jpg", MediaType: "image",
		SizeBytes: 1, Mtime: store.FormatTimestamp(time.Now()), CreatedAt: store.FormatTimestamp(time.Now()),
		UpdatedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatalf("插入资产失败: %v", err)
	}

	// 触发器建好的索引：清空后重建，内容一致（行数 = 资产数）
	n, err := q.RebuildAssetsFtsClear(ctx)
	if err != nil {
		t.Fatalf("清空失败: %v", err)
	}
	if n != 1 {
		t.Fatalf("清空应删除 1 行，得到 %d", n)
	}
	if err := RebuildIndex(ctx, q); err != nil {
		t.Fatalf("RebuildIndex 失败: %v", err)
	}
	items, err := q.ListAssetsFilteredDesc(ctx, db.ListAssetsFilteredDescParams{
		Sort: "default", RowLimit: 10,
		// COS 分区三态开关（browse.sql）：直连 store 层必须显式传 0/1，
		// NULL 会让三值逻辑把非 COS 行也排除（handler 侧恒传，见
		// newAssetFilters 注释）；本测试走常规分区缺省。source_is_other
		// 同此契约（2026-09-09 多值化后与 sources_json 组成 AND 短路对）。
		IncludeCos:    0,
		CosOnly:       0,
		SourceIsOther: 0,
		QJson:         `["标题"]`,
	})
	if err != nil {
		t.Fatalf("搜索失败: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("重建后搜索应命中 1 条，得到 %d", len(items))
	}
}
