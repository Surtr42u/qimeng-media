package authorattach

// asset_works_test.go：改名/物理删除的 TXT 作品行硬约束（ADR-0024 片段本体
// 唯一真相）。锁定：
//   - RenameAssetWorks 是「替换」不是「追加」：旧行消失、新行出现，块序/
//     编号行/来源区不动；多作者多片段；新行已存在幂等；旧行不存在 no-op；
//   - DropAssetWorks 只删当前关联 regular 作者的作品行，不碰其他作者/实例；
//   - 上传条目元数据：新行登记、旧行修剪；
//   - 回收站物理删除时资产行已级联删除，按文件名兜底（无同名资产才清理）。

import (
	"context"
	"reflect"
	"strings"
	"testing"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedAssetIn 在 seedAsset 已建的 lib-1 下再建一个资产行（多资产用例共用库；
// relPath 必须与既有行不同——UpsertAsset 以 (library_id, rel_path) 为冲突键）。
func seedAssetIn(t *testing.T, q *db.Queries, id, relPath, fileName string) {
	t.Helper()
	if _, err := q.UpsertAsset(context.Background(), db.UpsertAssetParams{
		AssetID: id, LibraryID: "lib-1", RelPath: relPath, FileName: fileName,
		MediaType: "image", SizeBytes: 1, Mtime: store.FormatTimestamp(testNow),
		CreatedAt: store.FormatTimestamp(testNow), UpdatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("建资产 %s 失败: %v", id, err)
	}
}

func persistTestFragments(t *testing.T, q *db.Queries, fixtures ...Source) {
	t.Helper()
	sources := make([]Source, len(fixtures))
	for i := range fixtures {
		sources[i] = fixtures[i]
		sources[i].ImportedAt = store.FormatTimestamp(testNow)
	}
	if err := PersistSources(context.Background(), q, testNow, sources); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
}

func loadTestSources(t *testing.T, q *db.Queries) []Source {
	t.Helper()
	sources, err := LoadSources(context.Background(), q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	return sources
}

func sourceContent(t *testing.T, sources []Source, filename string) string {
	t.Helper()
	for _, src := range sources {
		if src.Filename == filename {
			return src.Content
		}
	}
	t.Fatalf("片段 %s 不存在: %+v", filename, sources)
	return ""
}

func loadTestEntries(t *testing.T, q *db.Queries) map[string][]authoring.UploadEntry {
	t.Helper()
	entries, err := LoadUploadEntries(context.Background(), q)
	if err != nil {
		t.Fatalf("读取上传条目失败: %v", err)
	}
	return entries
}

func TestRenameAssetWorksReplacesOldLineAcrossAuthorsAndFragments(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-1", "old.png")
	seedAuthor(t, q, "aaa", "aaa")
	seedAuthor(t, q, "bbb", "bbb")
	seedLink(t, q, "asset-1", "aaa")
	seedLink(t, q, "asset-1", "bbb")
	persistTestFragments(t, q,
		Source{Filename: "f1.txt", Content: "1  aaa\n来源\nsite-a\n作品\nold.png\n"},
		Source{Filename: "f2.txt", Content: "1  bbb\n来源\nsite-b\n作品\nkeep.png\nold.png\n"},
		Source{Filename: "f3.txt", Content: "1  ccc\n作品\nold.png\n"},
	)
	if err := SaveUploadEntries(ctx, q, testNow, map[string][]authoring.UploadEntry{
		"f1.txt": {{AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"old.png"}}},
		"f2.txt": {{AuthorID: "bbb", DisplayName: "bbb", Names: []string{"bbb"}, Works: []string{"keep.png", "old.png"}}},
		"f3.txt": {{AuthorID: "ccc", DisplayName: "ccc", Names: []string{"ccc"}, Works: []string{"old.png"}}},
	}); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}

	if err := (&Service{}).RenameAssetWorks(ctx, q, testNow, "asset-1", "old.png", "new.png"); err != nil {
		t.Fatalf("RenameAssetWorks 失败: %v", err)
	}
	sources := loadTestSources(t, q)
	if got, want := sourceContent(t, sources, "f1.txt"), "1  aaa\n来源\nsite-a\n作品\nnew.png\n"; got != want {
		t.Errorf("f1 片段=\n%q\nwant=\n%q", got, want)
	}
	if got, want := sourceContent(t, sources, "f2.txt"), "1  bbb\n来源\nsite-b\n作品\nkeep.png\nnew.png\n"; got != want {
		t.Errorf("f2 片段=\n%q\nwant=\n%q", got, want)
	}
	if got, want := sourceContent(t, sources, "f3.txt"), "1  ccc\n作品\nold.png\n"; got != want {
		t.Errorf("未关联作者 ccc 被误改: %q", got)
	}
	entries := loadTestEntries(t, q)
	if got, want := entries["f1.txt"], []authoring.UploadEntry{{AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"new.png"}}}; !reflect.DeepEqual(got, want) {
		t.Errorf("f1 条目=%+v, want %+v", got, want)
	}
	if got, want := entries["f2.txt"], []authoring.UploadEntry{{AuthorID: "bbb", DisplayName: "bbb", Names: []string{"bbb"}, Works: []string{"keep.png", "new.png"}}}; !reflect.DeepEqual(got, want) {
		t.Errorf("f2 条目=%+v, want %+v", got, want)
	}
	if got, want := entries["f3.txt"], []authoring.UploadEntry{{AuthorID: "ccc", DisplayName: "ccc", Names: []string{"ccc"}, Works: []string{"old.png"}}}; !reflect.DeepEqual(got, want) {
		t.Errorf("未关联作者条目被误改: %+v", got)
	}

	// 幂等：同样的改名再来一次，片段与条目逐字不变。
	before := loadTestSources(t, q)
	beforeEntries := loadTestEntries(t, q)
	if err := (&Service{}).RenameAssetWorks(ctx, q, testNow, "asset-1", "old.png", "new.png"); err != nil {
		t.Fatalf("重复 RenameAssetWorks 失败: %v", err)
	}
	if after := loadTestSources(t, q); !reflect.DeepEqual(after, before) {
		t.Errorf("重复改名改动了片段:\nbefore=%+v\nafter=%+v", before, after)
	}
	if after := loadTestEntries(t, q); !reflect.DeepEqual(after, beforeEntries) {
		t.Errorf("重复改名改动了上传条目: %+v", after)
	}
}

func TestRenameAssetWorksWhenNewLineAlreadyExists(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-1", "old.png")
	seedAuthor(t, q, "aaa", "aaa")
	seedLink(t, q, "asset-1", "aaa")
	persistTestFragments(t, q, Source{Filename: "f1.txt", Content: "1  aaa\n来源\nsite-a\n作品\nold.png\nnew.png\n"})
	if err := SaveUploadEntries(ctx, q, testNow, map[string][]authoring.UploadEntry{
		"f1.txt": {{AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"new.png"}}},
	}); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}

	if err := (&Service{}).RenameAssetWorks(ctx, q, testNow, "asset-1", "old.png", "new.png"); err != nil {
		t.Fatalf("RenameAssetWorks 失败: %v", err)
	}
	if got, want := sourceContent(t, loadTestSources(t, q), "f1.txt"), "1  aaa\n来源\nsite-a\n作品\nnew.png\n"; got != want {
		t.Errorf("新行已存在时片段=%q, want %q", got, want)
	}
	entries := loadTestEntries(t, q)
	if got, want := entries["f1.txt"], []authoring.UploadEntry{{AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"new.png"}}}; !reflect.DeepEqual(got, want) {
		t.Errorf("新行已存在时不应重复登记: %+v", got)
	}
}

func TestRenameAssetWorksMissingOldLineIsNoop(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-1", "new.png")
	seedAuthor(t, q, "aaa", "aaa")
	seedLink(t, q, "asset-1", "aaa")
	persistTestFragments(t, q, Source{Filename: "f1.txt", Content: "1  aaa\n来源\nsite-a\n作品\nnew.png\n"})
	before := loadTestSources(t, q)
	if err := (&Service{}).RenameAssetWorks(ctx, q, testNow, "asset-1", "missing.png", "new.png"); err != nil {
		t.Fatalf("RenameAssetWorks 失败: %v", err)
	}
	if after := loadTestSources(t, q); !reflect.DeepEqual(after, before) {
		t.Errorf("旧行不存在时必须 no-op:\nbefore=%+v\nafter=%+v", before, after)
	}
}

func TestDropAssetWorksRemovesOnlyLinkedAuthorLines(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-1", "a.png")
	seedAssetIn(t, q, "asset-2", "sub/a.png", "a.png")
	seedAuthor(t, q, "aaa", "aaa")
	seedAuthor(t, q, "bbb", "bbb")
	seedLink(t, q, "asset-1", "aaa")
	seedLink(t, q, "asset-2", "bbb")
	persistTestFragments(t, q,
		Source{Filename: "f1.txt", Content: "1  aaa\n来源\nsite-a\n作品\na.png\n\n\n\n2  ccc\n作品\na.png\n"},
		Source{Filename: "f2.txt", Content: "1  bbb\n作品\na.png\n"},
	)
	if err := SaveUploadEntries(ctx, q, testNow, map[string][]authoring.UploadEntry{
		"f1.txt": {
			{AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"a.png"}},
			{AuthorID: "ccc", DisplayName: "ccc", Names: []string{"ccc"}, Works: []string{"a.png"}},
		},
		"f2.txt": {{AuthorID: "bbb", DisplayName: "bbb", Names: []string{"bbb"}, Works: []string{"a.png"}}},
	}); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}

	if err := (&Service{}).DropAssetWorks(ctx, q, testNow, "asset-1", "a.png"); err != nil {
		t.Fatalf("DropAssetWorks 失败: %v", err)
	}
	sources := loadTestSources(t, q)
	if got, want := sourceContent(t, sources, "f1.txt"), "1  aaa\n来源\nsite-a\n作品\n\n\n\n2  ccc\n作品\na.png\n"; got != want {
		t.Errorf("只应删除 aaa 的作品行: %q", got)
	}
	if got, want := sourceContent(t, sources, "f2.txt"), "1  bbb\n作品\na.png\n"; got != want {
		t.Errorf("其他资产的作者行被误删: %q", got)
	}
	entries := loadTestEntries(t, q)
	if got, want := entries["f1.txt"], []authoring.UploadEntry{{AuthorID: "ccc", DisplayName: "ccc", Names: []string{"ccc"}, Works: []string{"a.png"}}}; !reflect.DeepEqual(got, want) {
		t.Errorf("条目应只剪 aaa: %+v", got)
	}
	if got, want := entries["f2.txt"], []authoring.UploadEntry{{AuthorID: "bbb", DisplayName: "bbb", Names: []string{"bbb"}, Works: []string{"a.png"}}}; !reflect.DeepEqual(got, want) {
		t.Errorf("bbb 条目被误剪: %+v", got)
	}

	if err := (&Service{}).DropAssetWorks(ctx, q, testNow, "asset-2", "a.png"); err != nil {
		t.Fatalf("第二次 DropAssetWorks 失败: %v", err)
	}
	if got, want := sourceContent(t, loadTestSources(t, q), "f1.txt"), "1  aaa\n来源\nsite-a\n作品\n\n\n\n2  ccc\n作品\na.png\n"; got != want {
		t.Errorf("未关联作者行不得被第二次删除: %q", got)
	}
}

func TestDropAssetWorksAfterAssetRowGoneUsesFileNameFallback(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-1", "a.png")
	seedAuthor(t, q, "aaa", "aaa")
	seedLink(t, q, "asset-1", "aaa")
	persistTestFragments(t, q, Source{Filename: "f1.txt", Content: "1  aaa\n来源\nsite-a\n作品\na.png\n"})
	if err := SaveUploadEntries(ctx, q, testNow, map[string][]authoring.UploadEntry{
		"f1.txt": {{AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"a.png"}}},
	}); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}
	// 回收站物理删除时资产行已删、关联级联清空。
	if err := q.DeleteAsset(ctx, "asset-1"); err != nil {
		t.Fatalf("删除资产行失败: %v", err)
	}

	if err := (&Service{}).DropAssetWorks(ctx, q, testNow, "asset-1", "a.png"); err != nil {
		t.Fatalf("DropAssetWorks 失败: %v", err)
	}
	if got, want := sourceContent(t, loadTestSources(t, q), "f1.txt"), "1  aaa\n来源\nsite-a\n作品\n"; got != want {
		t.Errorf("资产行已消失时应按文件名清理残留行: %q", got)
	}
	if entries := loadTestEntries(t, q); len(entries["f1.txt"]) != 0 {
		t.Errorf("残留上传条目未修剪: %+v", entries)
	}

	// 同名资产仍存活时保留作品行：该文件名仍被使用，删行会制造新的孤儿行。
	seedAssetIn(t, q, "asset-2", "sub/a.png", "a.png")
	persistTestFragments(t, q, Source{Filename: "f1.txt", Content: "1  aaa\n来源\nsite-a\n作品\na.png\n"})
	if err := (&Service{}).DropAssetWorks(ctx, q, testNow, "asset-1", "a.png"); err != nil {
		t.Fatalf("DropAssetWorks 失败: %v", err)
	}
	if got := sourceContent(t, loadTestSources(t, q), "f1.txt"); !strings.Contains(got, "a.png") {
		t.Errorf("同名存活资产的作品行被误删: %q", got)
	}
}
