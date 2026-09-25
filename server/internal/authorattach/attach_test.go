package authorattach

// Apply 挂靠编排测试：真 SQLite 直测编排包（不经 HTTP，ADR-0019）。
// 覆盖：新建作者到自动片段 / 既有作者命中数组序第一个片段 / AuthorID
// 查无 → ErrAuthorNotFound / 大小写变体归并既有身份 / 幂等 / 条目元数据
// 只记新追加行。

import (
	"context"
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedAsset 建库 + 建资产行：asset_authors 有外键（foreign_keys(1) 连接级
// PRAGMA），关联写入需要真实资产行——真实上传流也是先入库资产再挂靠。
func seedAsset(t *testing.T, q *db.Queries, id, name string) {
	t.Helper()
	ctx := context.Background()
	if _, err := q.CreateLibrary(ctx, db.CreateLibraryParams{
		ID: "lib-1", Name: "测试库", RootPath: t.TempDir(), Kind: "normal",
		CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	if _, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: id, LibraryID: "lib-1", RelPath: name, FileName: name,
		MediaType: "image", SizeBytes: 1, Mtime: store.FormatTimestamp(testNow),
		CreatedAt: store.FormatTimestamp(testNow), UpdatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("建资产 %s 失败: %v", id, err)
	}
}

// mustApply 断言 Apply 成功并返回结果（失败即 Fatal）。
func mustApply(t *testing.T, q *db.Queries, req AttachRequest) AttachResult {
	t.Helper()
	res, err := (&Service{}).Apply(context.Background(), q, testNow, req)
	if err != nil {
		t.Fatalf("Apply 失败: %v", err)
	}
	return res
}

// authorRow 取 authors 表 + 关联计数行（ListAuthors 的内存查行）。
func authorRow(t *testing.T, q *db.Queries, id string) db.ListAuthorsRow {
	t.Helper()
	rows, err := q.ListAuthors(context.Background())
	if err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	for _, r := range rows {
		if r.ID == id {
			return r
		}
	}
	t.Fatalf("作者 %s 不在 authors 表中", id)
	return db.ListAuthorsRow{}
}

// 新作者 + 服务端无任何片段：自动创建承载片段，一次完成建块/挂靠/来源/
// 作者行/关联（REQ 验收 #4）。
func TestApplyNewAuthorAutoFragment(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "守望先锋  天使 1.png")

	res := mustApply(t, q, AttachRequest{
		AssetID: "asset-1", FinalName: "守望先锋  天使 1.png",
		AuthorName: "Night Cry", Sources: []string{"kemono"},
	})
	if res.AuthorID != "night_cry" || res.DisplayName != "Night Cry" {
		t.Errorf("身份=%q/%q, want night_cry/Night Cry", res.AuthorID, res.DisplayName)
	}
	if res.Fragment != authoring.AutoFragmentFilename || !res.CreatedAuthor {
		t.Errorf("Fragment=%q CreatedAuthor=%v, want %q/true", res.Fragment, res.CreatedAuthor, authoring.AutoFragmentFilename)
	}

	sources, err := LoadSources(context.Background(), q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if len(sources) != 1 || sources[0].Filename != authoring.AutoFragmentFilename {
		t.Fatalf("自动片段=%+v, want 单个 %q", sources, authoring.AutoFragmentFilename)
	}
	if sources[0].ImportedAt != store.FormatTimestamp(testNow) {
		t.Errorf("自动片段 ImportedAt=%q, want now（此后它即最近导入的片段）", sources[0].ImportedAt)
	}
	// 往返锁定：自动片段被正式解析管线原样读回。
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 || blocks[0].DisplayName != "Night Cry" {
		t.Fatalf("解析块=%+v, want 单个 Night Cry", blocks)
	}
	if want := []string{"kemono"}; !reflect.DeepEqual(blocks[0].Sources, want) {
		t.Errorf("Sources=%v, want %v", blocks[0].Sources, want)
	}
	if want := []string{"守望先锋  天使 1.png"}; !reflect.DeepEqual(blocks[0].Works, want) {
		t.Errorf("Works=%v, want %v", blocks[0].Works, want)
	}

	// 作者行 + 即时关联（挂靠即时可见，REQ 验收 #2）。
	if row := authorRow(t, q, "night_cry"); row.DisplayName != "Night Cry" || row.FileCount != 1 {
		t.Errorf("作者行=%+v, want DisplayName=Night Cry FileCount=1", row)
	}

	// 上传条目元数据。
	entries, err := LoadUploadEntries(context.Background(), q)
	if err != nil {
		t.Fatalf("读取上传条目失败: %v", err)
	}
	wantEntries := map[string][]authoring.UploadEntry{
		authoring.AutoFragmentFilename: {{
			AuthorID: "night_cry", DisplayName: "Night Cry", Names: []string{"Night Cry"},
			Works: []string{"守望先锋  天使 1.png"}, Sources: []string{"kemono"},
		}},
	}
	if !reflect.DeepEqual(entries, wantEntries) {
		t.Errorf("条目元数据=%+v, want %+v", entries, wantEntries)
	}
}

// 既有作者：命中其所在片段取数组序第一个（不是 MostRecent），目标片段
// ImportedAt 不变，只追加新行。
func TestApplyExistingAuthorFirstFragment(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "b.png")
	ctx := context.Background()
	older := store.FormatTimestamp(testNow)
	newer := store.FormatTimestamp(testNow.Add(time.Hour))
	frag := "1  bamhor\n出处  kemono\n作品\na.png\n"
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "老.txt", Content: frag, ImportedAt: older},
		{Filename: "新.txt", Content: "1  other\n作品\nx.png\n", ImportedAt: newer},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "bamhor", DisplayName: "bamhor", Type: authoring.AuthorTypeRegular, CreatedAt: older,
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}

	res := mustApply(t, q, AttachRequest{
		AssetID: "asset-1", FinalName: "b.png", AuthorID: "bamhor", Sources: []string{"pixiv"},
	})
	if res.Fragment != "老.txt" || res.CreatedAuthor {
		t.Errorf("Fragment=%q CreatedAuthor=%v, want 老.txt/false", res.Fragment, res.CreatedAuthor)
	}

	sources, _ := LoadSources(ctx, q)
	if sources[0].Filename != "老.txt" || sources[0].ImportedAt != older {
		t.Errorf("目标片段=%+v, want 老.txt 且 ImportedAt 不变", sources[0])
	}
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 {
		t.Fatalf("解析块数=%d, want 1", len(blocks))
	}
	if want := []string{"kemono", "pixiv"}; !reflect.DeepEqual(blocks[0].Sources, want) {
		t.Errorf("Sources=%v, want %v", blocks[0].Sources, want)
	}
	if want := []string{"a.png", "b.png"}; !reflect.DeepEqual(blocks[0].Works, want) {
		t.Errorf("Works=%v, want %v", blocks[0].Works, want)
	}
	if sources[1].Content != "1  other\n作品\nx.png\n" {
		t.Errorf("非目标片段被改动: %q", sources[1].Content)
	}
	if row := authorRow(t, q, "bamhor"); row.FileCount != 1 {
		t.Errorf("FileCount=%d, want 1", row.FileCount)
	}
}

// AuthorID 路径 + 多别名 displayName 作者无块：新建块编号行必须按空格分隔
// 各别名写回——整串当单别名会让解析回读的 GenerateAuthorID 漂移成幻影作者。
func TestApplyMultiAliasAuthorIDNoBlock(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "b.png")
	ctx := context.Background()
	frag := "1  other\n作品\nx.png\n"
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "新.txt", Content: frag, ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "night", DisplayName: "Night / Cry", Type: authoring.AuthorTypeRegular, CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}

	res := mustApply(t, q, AttachRequest{
		AssetID: "asset-1", FinalName: "b.png", AuthorID: "night",
	})
	if res.Fragment != "新.txt" {
		t.Errorf("Fragment=%q, want 新.txt", res.Fragment)
	}
	sources, _ := LoadSources(ctx, q)
	if !strings.Contains(sources[0].Content, "2  Night  Cry") {
		t.Errorf("编号行未按空格分隔别名写回: %q", sources[0].Content)
	}
	found := false
	for i := range sources {
		for _, b := range authoring.ParseAuthorBlocks(sources[i].Content) {
			if len(b.AuthorNames) > 0 && authoring.GenerateAuthorID(b.AuthorNames[0]) == "night" {
				found = true
				if !reflect.DeepEqual(b.AuthorNames, []string{"Night", "Cry"}) {
					t.Errorf("AuthorNames=%v, want [Night Cry]", b.AuthorNames)
				}
			}
		}
	}
	if !found {
		t.Fatalf("回读不到 id=night 的作者块（幻影作者形态）")
	}
}

// AuthorID 传入但 authors 表查无 → ErrAuthorNotFound，且无片段副作用。
func TestApplyAuthorIDNotFound(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "a.png")
	_, err := (&Service{}).Apply(context.Background(), q, testNow, AttachRequest{
		AssetID: "asset-1", FinalName: "a.png", AuthorID: "ghost",
	})
	if !errors.Is(err, ErrAuthorNotFound) {
		t.Fatalf("err=%v, want ErrAuthorNotFound", err)
	}
	sources, lerr := LoadSources(context.Background(), q)
	if lerr != nil {
		t.Fatalf("读取片段失败: %v", lerr)
	}
	if len(sources) != 0 {
		t.Errorf("失败路径产生片段副作用: %+v", sources)
	}
}

// 新建名是既有作者的大小写变体：归并同一身份，沿用既有显示名不裂分
// （REQ 验收 #4「再次输入大小写不同的同名时归并到该作者」）。
func TestApplyNewNameMergesIntoExistingIdentity(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "b.png")
	ctx := context.Background()
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "f.txt", Content: "1  Night Cry\n作品\na.png\n", ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "night_cry", DisplayName: "Night Cry", Type: authoring.AuthorTypeRegular, CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}

	res := mustApply(t, q, AttachRequest{
		AssetID: "asset-1", FinalName: "b.png", AuthorName: "night cry",
	})
	if res.AuthorID != "night_cry" || res.DisplayName != "Night Cry" {
		t.Errorf("身份=%q/%q, want night_cry/Night Cry（沿用既有显示名）", res.AuthorID, res.DisplayName)
	}
	if res.CreatedAuthor {
		t.Error("既有块存在时不应新建块")
	}
	sources, _ := LoadSources(ctx, q)
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 || blocks[0].DisplayName != "Night Cry" {
		t.Fatalf("解析块=%+v, want 单个 Night Cry（不裂成两个）", blocks)
	}
	if want := []string{"a.png", "b.png"}; !reflect.DeepEqual(blocks[0].Works, want) {
		t.Errorf("Works=%v, want %v", blocks[0].Works, want)
	}
}

// 幂等：同请求重复 Apply，片段内容稳定、条目元数据不膨胀、关联不重复
// （AddAssetAuthor ON CONFLICT DO NOTHING）。
func TestApplyIdempotent(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "a.png")
	req := AttachRequest{
		AssetID: "asset-1", FinalName: "a.png", AuthorName: "Night Cry", Sources: []string{"kemono"},
	}
	mustApply(t, q, req)
	after1, err := LoadSources(context.Background(), q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	entries1, err := LoadUploadEntries(context.Background(), q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}

	mustApply(t, q, req) // 重试/重复上传不得产生重复条目

	after2, _ := LoadSources(context.Background(), q)
	if !reflect.DeepEqual(after2, after1) {
		t.Errorf("二次 Apply 改变片段:\n%+v\n%+v", after1, after2)
	}
	entries2, _ := LoadUploadEntries(context.Background(), q)
	if !reflect.DeepEqual(entries2, entries1) {
		t.Errorf("二次 Apply 膨胀条目元数据:\n%+v\n%+v", entries1, entries2)
	}
	if row := authorRow(t, q, "night_cry"); row.FileCount != 1 {
		t.Errorf("FileCount=%d, want 1（关联不重复）", row.FileCount)
	}
}

// 条目元数据只记本次实际新追加的行：已存在的作品行/来源行不进元数据、
// 不动片段内容。
func TestApplyMetadataRecordsOnlyNewLines(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "x.png")
	ctx := context.Background()
	content := "1  aaa\n来源\nkemono\n作品\nx.png\n"
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "f.txt", Content: content, ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "aaa", DisplayName: "aaa", Type: authoring.AuthorTypeRegular, CreatedAt: store.FormatTimestamp(testNow),
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}

	// 全部已存在：内容不动，不写条目元数据。
	mustApply(t, q, AttachRequest{
		AssetID: "asset-1", FinalName: "x.png", AuthorID: "aaa", Sources: []string{"kemono"},
	})
	sources, _ := LoadSources(ctx, q)
	if sources[0].Content != content {
		t.Errorf("全存在时内容被改动:\n%q", sources[0].Content)
	}
	entries, err := LoadUploadEntries(ctx, q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	if got := entries["f.txt"]; len(got) != 0 {
		t.Errorf("全存在时写了条目元数据: %+v", got)
	}

	// 新作品行：元数据只记新行。
	mustApply(t, q, AttachRequest{AssetID: "asset-1", FinalName: "y.png", AuthorID: "aaa"})
	entries, err = LoadUploadEntries(ctx, q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	want := []authoring.UploadEntry{{
		AuthorID: "aaa", DisplayName: "aaa", Names: []string{"aaa"}, Works: []string{"y.png"},
	}}
	if got := entries["f.txt"]; !reflect.DeepEqual(got, want) {
		t.Errorf("条目元数据=%+v, want %+v（只记新追加行）", got, want)
	}
}

// 身份归一（阻断审查 1）：新建作者的 id 取 CanonicalAuthorNames(names[0])
// 而非原始输入——"Night  Cry" 原始输入生成 night__cry，会与块解析回读的
// night 分裂成两人、二次上传按 id 找不到块而反复追加重复块（幂等破坏）。
func TestApplyNewAuthorIdentityNormalization(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "dup.jpg")

	req := AttachRequest{
		AssetID: "asset-1", FinalName: "dup.jpg", AuthorName: "Night  Cry", Sources: []string{"kemono"},
	}
	res := mustApply(t, q, req)
	if res.AuthorID != "night" || res.DisplayName != "Night / Cry" {
		t.Fatalf("身份=%q/%q, want night / Night / Cry（canonical 首别名判定）", res.AuthorID, res.DisplayName)
	}

	sources, err := LoadSources(context.Background(), q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 1 || !reflect.DeepEqual(blocks[0].AuthorNames, []string{"Night", "Cry"}) {
		t.Fatalf("解析块=%+v, want 单块别名 [Night Cry]（两空格分隔写回）", blocks)
	}

	// authors 表恰一行 id=night（不裂两行）。
	rows, err := q.ListAuthors(context.Background())
	if err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	if len(rows) != 1 || rows[0].ID != "night" || rows[0].DisplayName != "Night / Cry" {
		t.Fatalf("作者行=%+v, want 单行 night / Night / Cry", rows)
	}

	// 同请求重复 Apply：幂等——块不重复、行不重复。
	mustApply(t, q, req)
	sources2, _ := LoadSources(context.Background(), q)
	if !reflect.DeepEqual(sources2, sources) {
		t.Fatalf("二次 Apply 改变片段:\n%+v\n%+v", sources, sources2)
	}
	blocks2 := authoring.ParseAuthorBlocks(sources2[0].Content)
	if len(blocks2) != 1 || len(blocks2[0].Works) != 1 || blocks2[0].Works[0] != "dup.jpg" {
		t.Fatalf("二次解析块=%+v, want 单块单作品行（不重复建块）", blocks2)
	}

	// 条目元数据 Names = canonical names（重建块的往返安全来源）。
	entries, err := LoadUploadEntries(context.Background(), q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	wantEntries := []authoring.UploadEntry{{
		AuthorID: "night", DisplayName: "Night / Cry", Names: []string{"Night", "Cry"},
		Works: []string{"dup.jpg"}, Sources: []string{"kemono"},
	}}
	if got := entries[authoring.AutoFragmentFilename]; !reflect.DeepEqual(got, wantEntries) {
		t.Fatalf("条目元数据=%+v, want %+v", got, wantEntries)
	}
}

// 括号备注变体的身份归一："bamhor[3D]" → id=bamhor（stripAliasNote 同构，
// 原始输入直接生成会是 bamhor3d，与块回读的 bamhor 分裂）。
func TestApplyNewNameBracketNoteIdentity(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "a.png")

	res := mustApply(t, q, AttachRequest{
		AssetID: "asset-1", FinalName: "a.png", AuthorName: "bamhor[3D]",
	})
	if res.AuthorID != "bamhor" {
		t.Fatalf("AuthorID=%q, want bamhor（备注截断后生成）", res.AuthorID)
	}
	sources, _ := LoadSources(context.Background(), q)
	if b := authoring.ParseAuthorBlocks(sources[0].Content); len(b) != 1 || !reflect.DeepEqual(b[0].AuthorNames, []string{"bamhor"}) {
		t.Fatalf("解析块=%+v, want 单块别名 [bamhor]", b)
	}
	if row := authorRow(t, q, "bamhor"); row.DisplayName != "bamhor" {
		t.Fatalf("作者行显示名=%q, want bamhor", row.DisplayName)
	}
}

// Apply 中途失败由调用方事务回滚兜底（REQ §3.3 第 6 条）：资产不存在 →
// AddAssetAuthor 外键违反 → Apply 报错 → Rollback 后片段/条目元数据/作者行
// 全部未变（无半更新状态）。
func TestApplyRollbackOnFailure(t *testing.T) {
	conn, err := store.Open(filepath.Join(t.TempDir(), "rollback.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() {
		if err := conn.Close(); err != nil {
			t.Logf("关闭测试库失败: %v", err)
		}
	})
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	q := db.New(conn)
	ctx := context.Background()

	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		t.Fatalf("开事务失败: %v", err)
	}
	_, err = (&Service{}).Apply(ctx, q.WithTx(tx), testNow, AttachRequest{
		AssetID: "ghost-asset", FinalName: "a.png", AuthorName: "Night Cry", Sources: []string{"kemono"},
	})
	if err == nil {
		t.Fatal("资产不存在时 Apply 应报错（外键违反）")
	}
	if err := tx.Rollback(); err != nil {
		t.Fatalf("回滚失败: %v", err)
	}

	sources, err := LoadSources(ctx, q)
	if err != nil || len(sources) != 0 {
		t.Errorf("回滚后片段=%+v err=%v, want 空", sources, err)
	}
	entries, err := LoadUploadEntries(ctx, q)
	if err != nil || len(entries) != 0 {
		t.Errorf("回滚后条目元数据=%+v err=%v, want 空", entries, err)
	}
	rows, err := q.ListAuthors(ctx)
	if err != nil || len(rows) != 0 {
		t.Errorf("回滚后作者行=%+v err=%v, want 空", rows, err)
	}
}

// 格式 C 作者挂靠（阻断审查跟进）：格式 C 导入的作者 Z 无任何块，库中已有
// 正常清单片段时 → Z 并入最近导入片段开新块（不自动建片段）、关联建立、
// 条目元数据 Names 重建后回读 id 不漂移（重建保留）。
func TestApplyAttachesFormatCAuthorIntoRecentFragment(t *testing.T) {
	q := newTestDB(t)
	seedAsset(t, q, "asset-1", "z1.png")
	ctx := context.Background()
	older := store.FormatTimestamp(testNow)
	newer := store.FormatTimestamp(testNow.Add(time.Hour))
	// 格式 C 只建作者行不存片段（与 httpapi importTxt 的格式 C 分支同构）。
	if err := q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "z", DisplayName: "Z", Type: authoring.AuthorTypeRegular, CreatedAt: older,
	}); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "清单.txt", Content: "1  别人\n作品\nx.png\n", ImportedAt: newer},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}

	res := mustApply(t, q, AttachRequest{AssetID: "asset-1", FinalName: "z1.png", AuthorID: "z"})
	if res.Fragment != "清单.txt" || !res.CreatedAuthor {
		t.Fatalf("Fragment=%q CreatedAuthor=%v, want 清单.txt/true（并入最近导入片段开新块）", res.Fragment, res.CreatedAuthor)
	}

	sources, _ := LoadSources(ctx, q)
	if len(sources) != 1 || sources[0].Filename != "清单.txt" {
		t.Fatalf("不应新建片段：sources=%+v", sources)
	}
	blocks := authoring.ParseAuthorBlocks(sources[0].Content)
	if len(blocks) != 2 {
		t.Fatalf("解析块数=%d, want 2（既有块 + Z 新块）:\n%s", len(blocks), sources[0].Content)
	}
	if zb := blocks[1]; !reflect.DeepEqual(zb.AuthorNames, []string{"Z"}) || !reflect.DeepEqual(zb.Works, []string{"z1.png"}) {
		t.Fatalf("Z 新块=%+v, want 别名 [Z] 作品 [z1.png]", zb)
	}
	if row := authorRow(t, q, "z"); row.FileCount != 1 {
		t.Fatalf("FileCount=%d, want 1（关联建立）", row.FileCount)
	}

	// 条目元数据挂在清单.txt 名下；Names 重建后回读 id == z（往返不漂移）。
	entries, err := LoadUploadEntries(ctx, q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	list := entries["清单.txt"]
	if len(list) != 1 || list[0].AuthorID != "z" || !reflect.DeepEqual(list[0].Names, []string{"Z"}) {
		t.Fatalf("条目=%+v, want 单条 z / Names [Z]", list)
	}
	rebuilt := authoring.MergeUploadEntries("1  别人\n作品\nx.png\n", list)
	for _, b := range authoring.ParseAuthorBlocks(rebuilt) {
		if len(b.AuthorNames) > 0 && authoring.GenerateAuthorID(b.AuthorNames[0]) == "z" {
			return // 重建保留：Z 块回来且身份不漂移
		}
	}
	t.Fatalf("Names 重建后 Z 块丢失或身份漂移:\n%s", rebuilt)
}
