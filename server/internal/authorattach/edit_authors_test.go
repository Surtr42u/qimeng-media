package authorattach

// ResolveOrCreateAuthors（按名新建/复用，2026-10-11 协议修订）与
// ReplaceAssetAuthors 自愈补行/条目登记的编排测试：真 SQLite 直测编排包
// （不经 HTTP，ADR-0019）。HTTP 层校验矩阵与端到端见 httpapi 集成测试
// （author_edit_newauthor_test.go）。

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// TestResolveOrCreateAuthorsReuseAndCreate：按名解析——新建（作者行落库、
// displayName=别名 " / " 连接）、复用（大小写与别名拆分变体归一到同一身份，
// 沿用既有显示名、不重复建行）、同批重名短路。
func TestResolveOrCreateAuthorsReuseAndCreate(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	// 既有常规作者：displayName 为多别名连接串（块 "1  Night  Cry" 的重建
	// 产物），身份 id=night（首别名 "Night" 的 GenerateAuthorID 产物）。
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "night", DisplayName: "Night / Cry", Type: authoring.AuthorTypeRegular,
		CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}

	// "night  cry"（双空格=别名分隔，大小写变体）→ 身份 night → 复用；
	// "Night  Cry" 同批重复 → 短路不重复建；首尾空白按规范身份 trim 归一。
	refs, err := (&Service{}).ResolveOrCreateAuthors(ctx, q,
		[]string{"新建作者", "night  cry", "Night  Cry", "  新建作者乙  "}, testNow)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	want := []AuthorRef{
		{ID: "新建作者", DisplayName: "新建作者"},
		{ID: "night", DisplayName: "Night / Cry"},
		{ID: "新建作者乙", DisplayName: "新建作者乙"},
	}
	if !reflect.DeepEqual(refs, want) {
		t.Fatalf("refs=%+v, want %+v", refs, want)
	}
	// 复用不重复建行：作者表恰好 3 行（预置 1 + 新建 2）。
	rows, err := q.ListAuthors(ctx)
	if err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	if len(rows) != 3 {
		t.Fatalf("作者表行数=%d, want 3（复用不建新行）: %+v", len(rows), rows)
	}
	for _, row := range rows {
		if row.ID == "night" && row.DisplayName != "Night / Cry" {
			t.Errorf("复用行显示名被改写：%+v", row)
		}
		if row.ID == "新建作者" && row.Type != authoring.AuthorTypeRegular {
			t.Errorf("新建行类型=%q, want regular", row.Type)
		}
	}
}

// TestResolveOrCreateAuthorsRejections：COS 作者行撞名（cos_ 前缀恰是
// GenerateAuthorID 的合法产物）报错不静默；规范化后无有效别名的名字返回
// ErrInvalidAuthorName（防御兜底）；被拒调用不改动作者表。
func TestResolveOrCreateAuthorsRejections(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "cos_x", DisplayName: "COS酱", Type: authoring.AuthorTypeCos,
		CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置 COS 作者失败: %v", err)
	}

	if _, err := (&Service{}).ResolveOrCreateAuthors(ctx, q, []string{"cos_x"}, testNow); err == nil {
		t.Fatal("与 COS 作者行撞名应报错（静默复用/覆盖都是事故）")
	}
	if _, err := (&Service{}).ResolveOrCreateAuthors(ctx, q, []string{"[3D]"}, testNow); !errors.Is(err, ErrInvalidAuthorName) {
		t.Fatalf("空 canonical 应 ErrInvalidAuthorName，得到 %v", err)
	}
	rows, err := q.ListAuthors(ctx)
	if err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	if len(rows) != 1 || rows[0].ID != "cos_x" || rows[0].Type != authoring.AuthorTypeCos {
		t.Fatalf("被拒调用不得改动作者表：%+v", rows)
	}
}

// TestReplaceAssetAuthorsSelfHealAndRegister：关联已在 DB、片段缺作品行
// （用户真实事故形态）→ 同一次替换把行补进块并登记上传条目（防覆盖式
// 重导入冲掉）；行已在块中 → 零变化幂等（片段与条目都不动）。
func TestReplaceAssetAuthorsSelfHealAndRegister(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-1", "f.jpg")
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "aaa", DisplayName: "aaa", Type: authoring.AuthorTypeRegular,
		CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "f.txt", Content: "1  aaa\n作品\na.jpg\n", ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	// 关联在 DB、片段缺 f.jpg 行：自愈前提。
	if err := q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: "asset-1", AuthorID: "aaa",
		CreatedAt: store.NullTimestamp(store.FormatTimestamp(testNow)), Origin: store.OriginClient,
	}); err != nil {
		t.Fatalf("预置关联失败: %v", err)
	}

	if err := (&Service{}).ReplaceAssetAuthors(ctx, q, testNow, "asset-1", "f.jpg",
		[]AuthorRef{{ID: "aaa", DisplayName: "aaa"}}); err != nil {
		t.Fatalf("替换失败: %v", err)
	}
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if len(sources) != 1 {
		t.Fatalf("片段数=%d, want 1", len(sources))
	}
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 {
		t.Fatalf("解析块数=%d, want 1:\n%s", len(blocks), sources[0].Content)
	}
	if want := []string{"a.jpg", "f.jpg"}; !reflect.DeepEqual(blocks[0].Works, want) {
		t.Fatalf("自愈后作品行=%v, want %v（缺行补上）", blocks[0].Works, want)
	}
	// 登记：实际新追加的 f.jpg 行进入条目元数据（Names=块别名，往返安全）。
	entries, err := LoadUploadEntries(ctx, q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	wantEntries := []authoring.UploadEntry{{
		AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"f.jpg"},
	}}
	if !reflect.DeepEqual(entries["f.txt"], wantEntries) {
		t.Fatalf("登记条目=%+v, want %+v", entries["f.txt"], wantEntries)
	}

	// 幂等：行已存在 → 片段与条目零变化。
	if err := (&Service{}).ReplaceAssetAuthors(ctx, q, testNow, "asset-1", "f.jpg",
		[]AuthorRef{{ID: "aaa", DisplayName: "aaa"}}); err != nil {
		t.Fatalf("重复替换失败: %v", err)
	}
	sources2, _ := LoadSources(ctx, q)
	if !reflect.DeepEqual(sources2, sources) {
		t.Errorf("重复替换改变片段:\n%+v\n%+v", sources, sources2)
	}
	entries2, _ := LoadUploadEntries(ctx, q)
	if !reflect.DeepEqual(entries2, entries) {
		t.Errorf("重复替换改变条目元数据:\n%+v\n%+v", entries, entries2)
	}
}

// TestReplaceAssetAuthorsNewBlockRegistersWholeEntry：无块新建路径登记整条
// （DisplayName/Names/Works 齐备）——重建缺失块所需的往返安全素材。
func TestReplaceAssetAuthorsNewBlockRegistersWholeEntry(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-2", "g.jpg")
	// 作者行先于关联存在（生产路径由 ResolveOrCreateAuthors 建行；
	// asset_authors 有外键，无行时关联写入失败）。
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "night", DisplayName: "Night / Cry", Type: authoring.AuthorTypeRegular,
		CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}
	if err := (&Service{}).ReplaceAssetAuthors(ctx, q, testNow, "asset-2", "g.jpg",
		[]AuthorRef{{ID: "night", DisplayName: "Night / Cry"}}); err != nil {
		t.Fatalf("替换失败: %v", err)
	}
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if len(sources) != 1 || sources[0].Filename != authoring.AutoFragmentFilename {
		t.Fatalf("无片段应自动创建承载片段：%+v", sources)
	}
	if blocks := authoring.ParseAuthorBlocks(sources[0].Content); len(blocks) != 1 ||
		len(blocks[0].AuthorNames) == 0 || authoring.GenerateAuthorID(blocks[0].AuthorNames[0]) != "night" {
		t.Fatalf("新块解析回读 id 应不漂移:\n%s", sources[0].Content)
	}
	entries, err := LoadUploadEntries(ctx, q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	wantEntries := []authoring.UploadEntry{{
		AuthorID: "night", DisplayName: "Night / Cry", Names: []string{"Night", "Cry"}, Works: []string{"g.jpg"},
	}}
	if !reflect.DeepEqual(entries[authoring.AutoFragmentFilename], wantEntries) {
		t.Fatalf("新块登记条目=%+v, want %+v", entries[authoring.AutoFragmentFilename], wantEntries)
	}
}
