package authorattach

// manage_test.go：作者管理编排（DeleteAuthor / RenameAuthor，ADR-0024 修订 5，
// 2026-10-13）与事故回归锁定。真 SQLite 直测编排包（不经 HTTP，ADR-0019）。
// 核心锁定：
//   - 删除三清（TXT 块 + 关联 + 行）且不伤及他人（片段/条目/关联/行）；
//   - 改建名：同身份仅显示名（幂等）；身份迁移行/关联/关注/条目全随迁；
//     撞既有身份 409 语义（ErrAuthorIDConflict）且零副作用；
//   - 事故回归（「选了后保存无效」）：单别名含「 / 」字面的历史形态作者行
//     无块时，来源写入必须落在身份自洽的块上并可回读（修复前写出的块 id
//     漂移、回读恒空）。

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedAuthor 预置常规作者行。
func seedAuthor(t *testing.T, q *db.Queries, id, display string) {
	t.Helper()
	if err := q.UpsertAuthor(context.Background(), db.UpsertAuthorParams{
		ID: id, DisplayName: display, Type: authoring.AuthorTypeRegular,
		CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者 %s 失败: %v", id, err)
	}
}

// seedLink 预置资产-作者关联（资产行由 seedAsset 先建）。
func seedLink(t *testing.T, q *db.Queries, assetID, authorID string) {
	t.Helper()
	if err := q.AddAssetAuthor(context.Background(), db.AddAssetAuthorParams{
		AssetID: assetID, AuthorID: authorID,
		CreatedAt: store.NullTimestamp(store.FormatTimestamp(testNow)),
		Origin:    store.OriginClient,
	}); err != nil {
		t.Fatalf("预置关联 %s-%s 失败: %v", assetID, authorID, err)
	}
}

// authorIDs 当前作者表全部 id（保序升序之外按 ListAuthors 原序）。
func authorIDs(t *testing.T, q *db.Queries) []string {
	t.Helper()
	rows, err := q.ListAuthors(context.Background())
	if err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	ids := make([]string, 0, len(rows))
	for _, r := range rows {
		ids = append(ids, r.ID)
	}
	return ids
}

// assetAuthorIDs 资产当前关联的作者 id 集。
func assetAuthorIDs(t *testing.T, q *db.Queries, assetID string) []string {
	t.Helper()
	refs, err := q.ListAssetAuthorRefs(context.Background(), assetID)
	if err != nil {
		t.Fatalf("查询资产作者失败: %v", err)
	}
	ids := make([]string, 0, len(refs))
	for _, r := range refs {
		ids = append(ids, r.ID)
	}
	return ids
}

// TestDeleteAuthorThreeWayCleanup：删除 = TXT 块 + 关联 + 行三清，且不伤及
// 同片段/同资产上的其他作者；上传条目只剪被删作者的。
func TestDeleteAuthorThreeWayCleanup(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-1", "a.jpg")
	seedAuthor(t, q, "aaa", "aaa")
	seedAuthor(t, q, "bbb", "bbb")
	if err := PersistSources(ctx, q, testNow, []Source{{
		Filename:   "f.txt",
		Content:    "1  aaa\n来源\nsite-a\n作品\na.jpg\n\n\n\n2  bbb\n作品\na.jpg\n",
		ImportedAt: store.FormatTimestamp(testNow),
	}}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	seedLink(t, q, "asset-1", "aaa")
	seedLink(t, q, "asset-1", "bbb")
	if err := SaveUploadEntries(ctx, q, testNow, map[string][]authoring.UploadEntry{
		"f.txt": {
			{AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"a.jpg"}},
			{AuthorID: "bbb", DisplayName: "bbb", Names: []string{"bbb"}, Works: []string{"a.jpg"}},
		},
	}); err != nil {
		t.Fatalf("预置条目失败: %v", err)
	}

	if err := (&Service{}).DeleteAuthor(ctx, q, testNow, "aaa"); err != nil {
		t.Fatalf("DeleteAuthor 失败: %v", err)
	}

	// 行：aaa 没了、bbb 保留。
	if got := authorIDs(t, q); !reflect.DeepEqual(got, []string{"bbb"}) {
		t.Errorf("作者表=%v, want [bbb]", got)
	}
	// 关联：只剩 bbb。
	if got := assetAuthorIDs(t, q, "asset-1"); !reflect.DeepEqual(got, []string{"bbb"}) {
		t.Errorf("资产关联=%v, want [bbb]", got)
	}
	// 片段：aaa 块没了、bbb 块完好。
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if len(sources) != 1 {
		t.Fatalf("片段数=%d, want 1", len(sources))
	}
	for _, b := range authoring.ParseAuthorBlocks(sources[0].Content) {
		if authoring.GenerateAuthorID(b.AuthorNames[0]) == "aaa" {
			t.Errorf("删除后片段仍有 aaa 块:\n%s", sources[0].Content)
		}
	}
	if !strings.Contains(sources[0].Content, "2  bbb") {
		t.Errorf("bbb 块被误动:\n%s", sources[0].Content)
	}
	// 条目：只剩 bbb。
	entries, err := LoadUploadEntries(ctx, q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	if list := entries["f.txt"]; len(list) != 1 || list[0].AuthorID != "bbb" {
		t.Errorf("条目=%+v, want 仅 bbb", list)
	}

	// 404 / 400 哨兵。
	if err := (&Service{}).DeleteAuthor(ctx, q, testNow, "不存在"); !errors.Is(err, ErrAuthorNotFound) {
		t.Errorf("不存在 err=%v, want ErrAuthorNotFound", err)
	}
	// COS 行直接用 UpsertAuthor 落库（既有 regular 行的 upsert 冲突分支不改
	// type，故必须用全新 id 预置）。
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "cos_y", DisplayName: "COS酱", Type: authoring.AuthorTypeCos,
		CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置 COS 行失败: %v", err)
	}
	if err := (&Service{}).DeleteAuthor(ctx, q, testNow, "cos_y"); !errors.Is(err, ErrAuthorNotRegular) {
		t.Errorf("COS err=%v, want ErrAuthorNotRegular", err)
	}
}

// TestDeleteAuthorRowOnlyAuthor：无块的零关联行（格式 C / 按名新建产物）
// 可直接删除，片段零改动。
func TestDeleteAuthorRowOnlyAuthor(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAuthor(t, q, "solo", "solo")
	if err := PersistSources(ctx, q, testNow, []Source{{
		Filename: "f.txt", Content: "1  keeper\n作品\nx.jpg\n", ImportedAt: store.FormatTimestamp(testNow),
	}}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	if err := (&Service{}).DeleteAuthor(ctx, q, testNow, "solo"); err != nil {
		t.Fatalf("DeleteAuthor 失败: %v", err)
	}
	if got := authorIDs(t, q); len(got) != 0 {
		t.Errorf("作者表=%v, want 空", got)
	}
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if sources[0].Content != "1  keeper\n作品\nx.jpg\n" {
		t.Errorf("片段被误动:\n%q", sources[0].Content)
	}
}

// TestRenameAuthorSameIdentity：新首别名派生 id 不变 → 仅显示名更新（块编号行
// 重写、作品/来源区零动）；同显示名重放幂等零写入。
func TestRenameAuthorSameIdentity(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAuthor(t, q, "aaa", "aaa")
	if err := PersistSources(ctx, q, testNow, []Source{{
		Filename:   "f.txt",
		Content:    "1  aaa\n来源\nsite-a\n作品\nx.jpg\n",
		ImportedAt: store.FormatTimestamp(testNow),
	}}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}

	ref, err := (&Service{}).RenameAuthor(ctx, q, testNow, "aaa", "aaa / 别名乙")
	if err != nil {
		t.Fatalf("RenameAuthor 失败: %v", err)
	}
	if ref.ID != "aaa" || ref.DisplayName != "aaa / 别名乙" {
		t.Fatalf("ref=%+v, want id=aaa display=aaa / 别名乙", ref)
	}
	rows, _ := q.ListAuthors(ctx)
	if len(rows) != 1 || rows[0].DisplayName != "aaa / 别名乙" {
		t.Fatalf("行=%+v, want 单行且显示名更新", rows)
	}
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if !strings.Contains(sources[0].Content, "1  aaa  别名乙\n") {
		t.Errorf("编号行未重写:\n%s", sources[0].Content)
	}
	if !strings.Contains(sources[0].Content, "来源\nsite-a\n作品\nx.jpg\n") {
		t.Errorf("作品/来源区被误动:\n%s", sources[0].Content)
	}

	// 幂等重放：零写入（内容逐字相等）。
	before := sources[0].Content
	if _, err := (&Service{}).RenameAuthor(ctx, q, testNow, "aaa", "aaa  别名乙"); err != nil {
		t.Fatalf("重放 RenameAuthor 失败: %v", err)
	}
	sources, _ = LoadSources(ctx, q)
	if sources[0].Content != before {
		t.Errorf("同显示名重放应零写入")
	}
}

// TestRenameAuthorIdentityMigration：新首别名派生新 id → 行/关联/关注/条目
// 全随迁，块编号行改新别名（旧 id 块不再存在）；撞既有身份返回
// ErrAuthorIDConflict 且零副作用。
func TestRenameAuthorIdentityMigration(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	seedAsset(t, q, "asset-1", "x.jpg")
	seedAuthor(t, q, "aaa", "aaa")
	seedLink(t, q, "asset-1", "aaa")
	// 关注标记随迁：置位 aaa。
	if _, err := q.SetAuthorFollow(ctx, db.SetAuthorFollowParams{Followed: 1, ID: "aaa"}); err != nil {
		t.Fatalf("置位关注失败: %v", err)
	}
	if err := PersistSources(ctx, q, testNow, []Source{{
		Filename:   "f.txt",
		Content:    "1  aaa\n来源\nsite-a\n作品\nx.jpg\n",
		ImportedAt: store.FormatTimestamp(testNow),
	}}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	if err := SaveUploadEntries(ctx, q, testNow, map[string][]authoring.UploadEntry{
		"f.txt": {{AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"x.jpg"}}},
	}); err != nil {
		t.Fatalf("预置条目失败: %v", err)
	}

	ref, err := (&Service{}).RenameAuthor(ctx, q, testNow, "aaa", "ccc")
	if err != nil {
		t.Fatalf("RenameAuthor 失败: %v", err)
	}
	if ref.ID != "ccc" {
		t.Fatalf("ref.ID=%q, want ccc", ref.ID)
	}
	// 行：只剩 ccc 且关注随迁。
	rows, _ := q.ListAuthors(ctx)
	if len(rows) != 1 || rows[0].ID != "ccc" || rows[0].Followed != 1 {
		t.Fatalf("行=%+v, want 单行 ccc followed=1", rows)
	}
	// 关联迁移。
	if got := assetAuthorIDs(t, q, "asset-1"); !reflect.DeepEqual(got, []string{"ccc"}) {
		t.Errorf("关联=%v, want [ccc]", got)
	}
	// 片段：编号行改 ccc（旧块身份消失、新身份块存在且作品/来源保真）。
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 {
		t.Fatalf("块数=%d, want 1:\n%s", len(blocks), sources[0].Content)
	}
	if authoring.GenerateAuthorID(blocks[0].AuthorNames[0]) != "ccc" {
		t.Errorf("块身份=%q, want ccc", blocks[0].AuthorNames[0])
	}
	if !reflect.DeepEqual(blocks[0].Sources, []string{"site-a"}) || !reflect.DeepEqual(blocks[0].Works, []string{"x.jpg"}) {
		t.Errorf("来源/作品=%v/%v 被误动", blocks[0].Sources, blocks[0].Works)
	}
	// 条目改指新身份。
	entries, _ := LoadUploadEntries(ctx, q)
	if list := entries["f.txt"]; len(list) != 1 || list[0].AuthorID != "ccc" || list[0].Names[0] != "ccc" {
		t.Errorf("条目=%+v, want 改指 ccc", list)
	}

	// 撞既有身份：预置 ccc2 后 aaa2 改名到 ccc2 → 冲突且零副作用。
	seedAuthor(t, q, "ccc2", "ccc2")
	seedAuthor(t, q, "aaa2", "aaa2")
	if _, err := (&Service{}).RenameAuthor(ctx, q, testNow, "aaa2", "ccc2"); !errors.Is(err, ErrAuthorIDConflict) {
		t.Fatalf("冲突 err=%v, want ErrAuthorIDConflict", err)
	}
	if _, err := (&Service{}).RenameAuthor(ctx, q, testNow, "不存在", "x"); !errors.Is(err, ErrAuthorNotFound) {
		t.Errorf("不存在 err=%v, want ErrAuthorNotFound", err)
	}
}

// TestReplaceAuthorSourcesSingleAliasSlashDisplay 事故回归：单别名含「 / 」
// 字面的历史作者行（id 由整串派生，块缺失）写来源——块必须落在身份自洽的
// 别名形态上（回读 id == 行 id）、来源可回读；修复前写出的块 id 漂移
// （hornyherring），编辑页回读恒空「选了后保存无效」。
func TestReplaceAuthorSourcesSingleAliasSlashDisplay(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	const rowID = "hornyherring__horny_herring_studios"
	const display = "hornyherring / Horny Herring Studios"
	seedAuthor(t, q, rowID, display)

	saved, err := (&Service{}).ReplaceAuthorSources(ctx, q, testNow, rowID, display,
		[]string{"hanime1", "小红车"}, SourcesModeReplace)
	if err != nil {
		t.Fatalf("ReplaceAuthorSources 失败: %v", err)
	}
	if !reflect.DeepEqual(saved, []string{"hanime1", "小红车"}) {
		t.Fatalf("回显=%v, want [hanime1 小红车]", saved)
	}
	// 块身份自洽：片段里出现的块 id 必须是行 id，而不是拆分首别名派生的 id。
	sources, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 {
		t.Fatalf("块数=%d, want 1:\n%s", len(blocks), sources[0].Content)
	}
	if got := authoring.GenerateAuthorID(blocks[0].AuthorNames[0]); got != rowID {
		t.Fatalf("块身份=%q, want %q（写块漂移回归）:\n%s", got, rowID, sources[0].Content)
	}
	// 回读：GET 语义（AuthorSources）拿得到刚写入的来源。
	echo, err := (&Service{}).AuthorSources(ctx, q, rowID)
	if err != nil {
		t.Fatalf("AuthorSources 失败: %v", err)
	}
	if !reflect.DeepEqual(echo, []string{"hanime1", "小红车"}) {
		t.Fatalf("回读=%v, want [hanime1 小红车]（保存无效回归）", echo)
	}
	// 重放：幂等（第二次写入回读相同）。
	if _, err := (&Service{}).ReplaceAuthorSources(ctx, q, testNow, rowID, display,
		[]string{"hanime1", "小红车", "r34"}, SourcesModeReplace); err != nil {
		t.Fatalf("重放失败: %v", err)
	}
	echo, _ = (&Service{}).AuthorSources(ctx, q, rowID)
	if !reflect.DeepEqual(echo, []string{"hanime1", "小红车", "r34"}) {
		t.Fatalf("二次回读=%v, want 含 r34", echo)
	}
}
