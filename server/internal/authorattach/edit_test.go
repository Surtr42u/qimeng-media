package authorattach

// ReplaceAuthorSources 的 append 分支编排测试（mode 语义，ADR-0024 编辑 +
// 上传流程自动挂靠并入）：真 SQLite 直测编排包（不经 HTTP，ADR-0019）。
// replace 分支与块命中/新建块落点由既有 HTTP 层测试锁定（httpapi
// TestAuthorSourcesReplace / TestAuthorSourcesNewBlockInRecentFragment），
// 此处只测 append。

import (
	"context"
	"reflect"
	"strings"
	"testing"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// TestReplaceAuthorSourcesAppendExistingBlock：块命中 append = 并入去重、
// 永不覆盖（[site-a] ∪ [site-a pixiv] → [site-a pixiv]）；回显=既有区 ∪
// 新增（保序，非请求输入）；作品行不动；目标片段 ImportedAt 不变；条目
// 元数据只修剪不新增（append 无移除行，PruneUploadEntries 恒 no-op，且不为
// 新行新增条目）；重复调用幂等（内容与条目零变化）。
func TestReplaceAuthorSourcesAppendExistingBlock(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "f.txt", Content: "1  aaa\n来源\nsite-a\n作品\nx.png\n", ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "aaa", DisplayName: "aaa", Type: authoring.AuthorTypeRegular, CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}
	// 存量上传条目（append 前预置：append 追加 pixiv 后它必须原样保留）。
	wantEntries := []authoring.UploadEntry{{
		AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"},
		Works: []string{"x.png"}, Sources: []string{"site-a"},
	}}
	if err := SaveUploadEntries(ctx, q, testNow, map[string][]authoring.UploadEntry{
		"f.txt": wantEntries,
	}); err != nil {
		t.Fatalf("预置条目失败: %v", err)
	}

	saved, err := (&Service{}).ReplaceAuthorSources(ctx, q, testNow, "aaa", "aaa",
		[]string{"site-a", "pixiv"}, SourcesModeAppend)
	if err != nil {
		t.Fatalf("append 写入失败: %v", err)
	}
	if want := []string{"site-a", "pixiv"}; !reflect.DeepEqual(saved, want) {
		t.Errorf("回显=%v, want %v（既有区在前保序去重）", saved, want)
	}
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if len(sources) != 1 || sources[0].Filename != "f.txt" || sources[0].ImportedAt != store.FormatTimestamp(testNow) {
		t.Fatalf("目标片段=%+v, want f.txt 且 ImportedAt 不变", sources)
	}
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 {
		t.Fatalf("解析块数=%d, want 1:\n%s", len(blocks), sources[0].Content)
	}
	if want := []string{"site-a", "pixiv"}; !reflect.DeepEqual(blocks[0].Sources, want) {
		t.Errorf("片段来源区=%v, want %v（并入非覆盖）", blocks[0].Sources, want)
	}
	if want := []string{"x.png"}; !reflect.DeepEqual(blocks[0].Works, want) {
		t.Errorf("作品行=%v, want %v（不受影响）", blocks[0].Works, want)
	}
	entries, err := LoadUploadEntries(ctx, q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	if got := entries["f.txt"]; !reflect.DeepEqual(got, wantEntries) {
		t.Errorf("条目元数据=%+v, want 原样（只修剪不新增）", got)
	}

	// 重复 append 同内容：幂等——片段与条目零变化。
	if _, err := (&Service{}).ReplaceAuthorSources(ctx, q, testNow, "aaa", "aaa",
		[]string{"site-a", "pixiv"}, SourcesModeAppend); err != nil {
		t.Fatalf("重复 append 失败: %v", err)
	}
	sources2, _ := LoadSources(ctx, q)
	if !reflect.DeepEqual(sources2, sources) {
		t.Errorf("重复 append 改变片段:\n%+v\n%+v", sources, sources2)
	}
	entries2, _ := LoadUploadEntries(ctx, q)
	if !reflect.DeepEqual(entries2, entries) {
		t.Errorf("重复 append 改变条目元数据:\n%+v\n%+v", entries, entries2)
	}
}

// TestReplaceAuthorSourcesAppendNoBlockAuthor：无块作者 append = 与 replace
// 同款新建块路径（并入对象不存在，无「覆盖」问题）——多别名 displayName
// 编号行按空格分隔各别名写回、解析回读 id 不漂移（displayNameAliases 口径
// 在 append 路径同样生效）、不新建片段（并入最近导入片段）。
func TestReplaceAuthorSourcesAppendNoBlockAuthor(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "night", DisplayName: "Night / Cry", Type: authoring.AuthorTypeRegular, CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "清单.txt", Content: "1  别人\n作品\nx.png\n", ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}

	saved, err := (&Service{}).ReplaceAuthorSources(ctx, q, testNow, "night", "Night / Cry",
		[]string{"forum-c"}, SourcesModeAppend)
	if err != nil {
		t.Fatalf("append 写入失败: %v", err)
	}
	if want := []string{"forum-c"}; !reflect.DeepEqual(saved, want) {
		t.Errorf("回显=%v, want %v", saved, want)
	}
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if len(sources) != 1 || sources[0].Filename != "清单.txt" {
		t.Fatalf("不应新建片段：sources=%+v", sources)
	}
	if !strings.Contains(sources[0].Content, "2  Night  Cry") {
		t.Errorf("编号行未按空格分隔别名写回（漂移形态 \"2  Night / Cry\"）:\n%s", sources[0].Content)
	}
	found := false
	for _, b := range authoring.ParseAuthorBlocks(sources[0].Content) {
		if len(b.AuthorNames) > 0 && authoring.GenerateAuthorID(b.AuthorNames[0]) == "night" {
			found = true
			if want := []string{"forum-c"}; !reflect.DeepEqual(b.Sources, want) {
				t.Errorf("新块来源区=%v, want %v", b.Sources, want)
			}
		}
	}
	if !found {
		t.Fatalf("回读不到 id=night 的作者块（幻影作者形态）:\n%s", sources[0].Content)
	}
	if got := authoring.ParseAuthorBlocks(sources[0].Content); len(got) != 2 {
		t.Errorf("解析块数=%d, want 2（既有块 + night 新块）", len(got))
	}
}
