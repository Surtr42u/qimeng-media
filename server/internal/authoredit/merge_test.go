package authoredit

// merge_test.go：作者合并用例（POST /authors/merge）的编排级回归。真 SQLite
// 合成库直测本包事务边界（不经 HTTP）：
//   - 正常合并：TXT 块/别名/作品行/来源、关联迁移与双端去重、关注并集、
//     档案迁移、上传条目改指、源身份删除、提交后镜像刷新；
//   - ADR-0032 溯源：迁移行保留原 origin/created_at，双端重复时保留 target 行；
//   - 目标无 TXT 块时在源片段新建目标块（身份往返安全）；
//   - 两端都有档案时保留目标、源档案随源删除（不静默覆盖）；
//   - 哨兵语义（COS/同 ID/不存在）与失败整体回滚（零副作用）、重复合并 404。
// 本地不执行（仓库规定业务测试走云端 CI）。

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/creatorprofile"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedFragments 写入多份合成片段（片段数组顺序 = 传入顺序）。
func seedFragments(t *testing.T, q *db.Queries, fragments ...authorattach.Source) {
	t.Helper()
	if err := authorattach.PersistSources(context.Background(), q, testNow, fragments); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
}

// seedLinkAt 预置带指定溯源（created_at/origin）的资产-作者关联。
func seedLinkAt(t *testing.T, q *db.Queries, assetID, authorID, createdAt, origin string) {
	t.Helper()
	if err := q.AddAssetAuthor(context.Background(), db.AddAssetAuthorParams{
		AssetID: assetID, AuthorID: authorID,
		CreatedAt: store.NullTimestamp(createdAt), Origin: origin,
	}); err != nil {
		t.Fatalf("预置关联 %s-%s 失败: %v", assetID, authorID, err)
	}
}

// seedProfile 预置作者附属档案（人工候选资料）。
func seedProfile(t *testing.T, q *db.Queries, id, nickname string, revision int64) {
	t.Helper()
	p := creatorprofile.Empty(id)
	p.Nickname = nickname
	p.Status = "candidate"
	p.Revision = revision
	if err := creatorprofile.Persist(context.Background(), q, p, testNow); err != nil {
		t.Fatalf("预置档案 %s 失败: %v", id, err)
	}
}

// fragmentByFilename 读取指定片段（不存在即 Fatal）。
func fragmentByFilename(t *testing.T, q *db.Queries, filename string) authorattach.Source {
	t.Helper()
	for _, src := range fragmentContents(t, q) {
		if src.Filename == filename {
			return src
		}
	}
	t.Fatalf("片段 %q 不存在", filename)
	return authorattach.Source{}
}

// authorBlockOf 解析 content 中指定作者块（不存在即 Fatal）。
func authorBlockOf(t *testing.T, content, authorID string) authoring.AuthorBlock {
	t.Helper()
	for _, block := range authoring.ParseAuthorBlocks(content) {
		if len(block.AuthorNames) > 0 && authoring.GenerateAuthorID(block.AuthorNames[0]) == authorID {
			return block
		}
	}
	t.Fatalf("片段中不存在作者 %s 的块:\n%s", authorID, content)
	return authoring.AuthorBlock{}
}

// associationOf 读取资产某作者关联的溯源行（不存在 ok=false）。
func associationOf(t *testing.T, q *db.Queries, assetID, authorID string) (db.ListAssetAuthorRefsRow, bool) {
	t.Helper()
	rows, err := q.ListAssetAuthorRefs(context.Background(), assetID)
	if err != nil {
		t.Fatalf("查询资产作者失败: %v", err)
	}
	for _, row := range rows {
		if row.ID == authorID {
			return row, true
		}
	}
	return db.ListAssetAuthorRefsRow{}, false
}

// authorRow 从 ListAuthors 取指定作者行（不存在 ok=false）。
func authorRow(t *testing.T, q *db.Queries, id string) (db.ListAuthorsRow, bool) {
	t.Helper()
	rows, err := q.ListAuthors(context.Background())
	if err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	for _, row := range rows {
		if row.ID == id {
			return row, true
		}
	}
	return db.ListAuthorsRow{}, false
}

// TestMergeAuthorsFullSlice：正常合并的完整切片——TXT 块并入目标片段、
// 关联迁移与双端去重（target 行 provenance 原样）、关注并集、档案迁移、
// 上传条目随目标块汇入、源身份删除、镜像提交后刷新、重复合并 404。
func TestMergeAuthorsFullSlice(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	seedLibrary(t, e.q, "lib-1", "normal")
	seedAsset(t, e.q, "asset-src", "lib-1", "s.jpg")
	seedAsset(t, e.q, "asset-both", "lib-1", "both.jpg")
	seedAsset(t, e.q, "asset-target", "lib-1", "t.jpg")

	src := authoring.GenerateAuthorID("源乙")
	tgt := authoring.GenerateAuthorID("目标甲")
	seedAuthorRow(t, e.q, tgt, "目标甲", authoring.AuthorTypeRegular)
	seedAuthorRow(t, e.q, src, "源乙", authoring.AuthorTypeRegular)
	if _, err := e.q.SetAuthorFollow(ctx, db.SetAuthorFollowParams{Followed: 1, ID: src}); err != nil {
		t.Fatalf("预置源关注失败: %v", err)
	}

	targetContent := "1  目标甲  目标别名\n来源  site-t\n作品\nt.jpg\n"
	sourceContent := "1  源乙  源别名\n来源  site-s\n作品\ns.jpg\n\n\n\n2  旁观者\n作品\nboth.jpg\n"
	seedFragments(t, e.q,
		authorattach.Source{Filename: "目标片段.txt", Content: targetContent, ImportedAt: store.FormatTimestamp(testNow)},
		authorattach.Source{Filename: "源片段.txt", Content: sourceContent, ImportedAt: store.FormatTimestamp(testNow)},
	)

	t1 := store.FormatTimestamp(time.Date(2026, 10, 1, 1, 0, 0, 0, time.UTC))
	t2 := store.FormatTimestamp(time.Date(2026, 10, 2, 1, 0, 0, 0, time.UTC))
	t3 := store.FormatTimestamp(time.Date(2026, 10, 3, 1, 0, 0, 0, time.UTC))
	t4 := store.FormatTimestamp(time.Date(2026, 10, 4, 1, 0, 0, 0, time.UTC))
	seedLinkAt(t, e.q, "asset-src", src, t1, store.OriginTXT)
	seedLinkAt(t, e.q, "asset-both", src, t2, store.OriginTXT)
	seedLinkAt(t, e.q, "asset-both", tgt, t3, store.OriginImport)
	seedLinkAt(t, e.q, "asset-target", tgt, t4, store.OriginScanner)

	seededEntries := map[string][]authoring.UploadEntry{
		"目标片段.txt": {{
			AuthorID: tgt, DisplayName: "目标甲", Names: []string{"目标甲", "目标别名"},
			Works: []string{"t.jpg"}, Sources: []string{"site-t"},
		}},
		"源片段.txt": {{
			AuthorID: src, DisplayName: "源乙", Names: []string{"源乙", "源别名"},
			Works: []string{"s.jpg"}, Sources: []string{"site-s"},
		}},
	}
	if err := authorattach.SaveUploadEntries(ctx, e.q, testNow, seededEntries); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}
	seedProfile(t, e.q, src, "源资料", 3)

	mirrorPath := filepath.Join(t.TempDir(), "mirror.txt")
	if err := authorattach.SaveMirrorConfig(ctx, e.q, testNow, authorattach.MirrorConfig{
		Path: mirrorPath, FragmentFilename: "目标片段.txt",
	}); err != nil {
		t.Fatalf("预置镜像配置失败: %v", err)
	}

	result, err := e.svc.MergeAuthors(ctx, src, tgt)
	if err != nil {
		t.Fatalf("合并失败: %v", err)
	}
	want := authorattach.MergeResult{
		SourceAuthorID: src, TargetAuthorID: tgt,
		AssetsMoved: 1, AssociationsDeduped: 1,
		WorksMerged: 1, SourcesMerged: 1, AliasesMerged: 2,
		FollowsMerged: true, ProfileMoved: true, UploadEntriesMoved: 1,
		DeletedSource: true,
	}
	if result != want {
		t.Fatalf("合并结果=%+v\nwant=%+v", result, want)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{tgt}) {
		t.Fatalf("作者表=%v, want 仅 target %s", got, tgt)
	}
	if row, ok := authorRow(t, e.q, tgt); !ok || row.Followed != 1 {
		t.Fatalf("target 关注标记=%+v, want followed=1", row)
	}

	// TXT：目标块在目标片段原位，别名/作品/来源并入；源块删除，旁观者保留。
	target := fragmentByFilename(t, e.q, "目标片段.txt")
	block := authorBlockOf(t, target.Content, tgt)
	if wantAliases := []string{"目标甲", "目标别名", "源乙", "源别名"}; !reflect.DeepEqual(block.AuthorNames, wantAliases) {
		t.Fatalf("目标块别名=%v, want %v\n%s", block.AuthorNames, wantAliases, target.Content)
	}
	if !reflect.DeepEqual(block.Works, []string{"t.jpg", "s.jpg"}) {
		t.Fatalf("目标块作品行=%v, want [t.jpg s.jpg]", block.Works)
	}
	if !reflect.DeepEqual(block.Sources, []string{"site-t", "site-s"}) {
		t.Fatalf("目标块来源区=%v, want [site-t site-s]", block.Sources)
	}
	sourceFragment := fragmentByFilename(t, e.q, "源片段.txt")
	for _, b := range authoring.ParseAuthorBlocks(sourceFragment.Content) {
		if len(b.AuthorNames) > 0 && authoring.GenerateAuthorID(b.AuthorNames[0]) == src {
			t.Fatalf("源块未删除:\n%s", sourceFragment.Content)
		}
	}
	if bystander := authorBlockOf(t, sourceFragment.Content, authoring.GenerateAuthorID("旁观者")); !reflect.DeepEqual(bystander.Works, []string{"both.jpg"}) {
		t.Fatalf("旁观者块被误动：%+v", bystander)
	}

	// 关联：source 行迁移并保留溯源；双端重复保留 target 行；target 既有行零动。
	if got := assetAuthorIDs(t, e.q, "asset-src"); !sameUnordered(got, []string{tgt}) {
		t.Fatalf("asset-src 关联=%v, want [%s]", got, tgt)
	}
	moved, ok := associationOf(t, e.q, "asset-src", tgt)
	if !ok || moved.Origin != store.OriginTXT || moved.CreatedAt.String != t1 {
		t.Fatalf("迁移关联未保留溯源：%+v（want origin=txt created=%s）", moved, t1)
	}
	if got := assetAuthorIDs(t, e.q, "asset-both"); !sameUnordered(got, []string{tgt}) {
		t.Fatalf("asset-both 关联应只剩 target：%v", got)
	}
	kept, ok := associationOf(t, e.q, "asset-both", tgt)
	if !ok || kept.Origin != store.OriginImport || kept.CreatedAt.String != t3 {
		t.Fatalf("双端重复应保留 target 行溯源：%+v（want origin=import created=%s）", kept, t3)
	}
	targetOnly, ok := associationOf(t, e.q, "asset-target", tgt)
	if !ok || targetOnly.Origin != store.OriginScanner || targetOnly.CreatedAt.String != t4 {
		t.Fatalf("target 既有行被改动：%+v", targetOnly)
	}

	// 档案：源档案迁到 target（revision 递增），源身份档案随删除清空。
	targetProfile, err := creatorprofile.Load(ctx, e.q, tgt)
	if err != nil || targetProfile.Nickname != "源资料" || targetProfile.Revision != 4 {
		t.Fatalf("target 档案=%+v err=%v, want nickname=源资料 revision=4", targetProfile, err)
	}
	sourceProfile, err := creatorprofile.Load(ctx, e.q, src)
	if err != nil || !creatorprofile.IsEmpty(sourceProfile) {
		t.Fatalf("源档案应随源删除清空：%+v err=%v", sourceProfile, err)
	}

	// 上传条目：源片段条目随目标块汇入目标片段，Names/DisplayName 同步合并别名。
	entries := uploadEntries(t, e.q)
	if _, exists := entries["源片段.txt"]; exists {
		t.Fatalf("源片段条目未清理：%+v", entries["源片段.txt"])
	}
	list := entries["目标片段.txt"]
	if len(list) != 1 {
		t.Fatalf("目标片段条目数=%d, want 1：%+v", len(list), list)
	}
	entry := list[0]
	if entry.AuthorID != tgt || entry.DisplayName != "目标甲 / 目标别名 / 源乙 / 源别名" {
		t.Fatalf("条目身份/显示名未同步：%+v", entry)
	}
	if !reflect.DeepEqual(entry.Names, []string{"目标甲", "目标别名", "源乙", "源别名"}) {
		t.Fatalf("条目 Names 未同步目标块别名：%v", entry.Names)
	}
	if !reflect.DeepEqual(entry.Works, []string{"t.jpg", "s.jpg"}) || !reflect.DeepEqual(entry.Sources, []string{"site-t", "site-s"}) {
		t.Fatalf("条目作品/来源未合并去重：%+v", entry)
	}

	// 镜像：Commit 成功后按配置片段刷新，内容与片段真相逐字一致。
	mirror, err := os.ReadFile(mirrorPath)
	if err != nil {
		t.Fatalf("成功路径应刷新镜像: %v", err)
	}
	if string(mirror) != target.Content {
		t.Fatalf("镜像内容与目标片段不一致：\n--- 镜像 ---\n%s\n--- 片段 ---\n%s", mirror, target.Content)
	}

	// 重复合并：源已删除 → 404 语义，且不再改动任何数据。
	if _, err := e.svc.MergeAuthors(ctx, src, tgt); !errors.Is(err, authorattach.ErrAuthorNotFound) {
		t.Fatalf("重复合并错误=%v, want ErrAuthorNotFound", err)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{tgt}) {
		t.Fatalf("重复合并后作者表被改动：%v", got)
	}
}

// TestMergeAuthorsTargetWithoutBlock：目标无 TXT 块（格式 C/按名新建）而源
// 有块时，在首个源块所在片段新建目标块（编号行取目标身份别名），源块删除；
// 上传条目随新目标块留在该片段并同步别名。
func TestMergeAuthorsTargetWithoutBlock(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	src := authoring.GenerateAuthorID("源有块")
	tgt := authoring.GenerateAuthorID("目标无块")
	seedAuthorRow(t, e.q, tgt, "目标无块", authoring.AuthorTypeRegular)
	seedAuthorRow(t, e.q, src, "源有块", authoring.AuthorTypeRegular)
	content := "1  源有块  源别名\n来源  site-s\n作品\ns.jpg\n"
	seedFragments(t, e.q, authorattach.Source{
		Filename: "来源片段.txt", Content: content, ImportedAt: store.FormatTimestamp(testNow),
	})
	if err := authorattach.SaveUploadEntries(ctx, e.q, testNow, map[string][]authoring.UploadEntry{
		"来源片段.txt": {{
			AuthorID: src, DisplayName: "源有块", Names: []string{"源有块", "源别名"},
			Works: []string{"s.jpg"}, Sources: []string{"site-s"},
		}},
	}); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}

	result, err := e.svc.MergeAuthors(ctx, src, tgt)
	if err != nil {
		t.Fatalf("合并失败: %v", err)
	}
	if result.AliasesMerged != 2 || result.WorksMerged != 1 || result.SourcesMerged != 1 {
		t.Fatalf("计数=%+v, want aliases=2 works=1 sources=1", result)
	}
	fragment := fragmentByFilename(t, e.q, "来源片段.txt")
	block := authorBlockOf(t, fragment.Content, tgt)
	if want := []string{"目标无块", "源有块", "源别名"}; !reflect.DeepEqual(block.AuthorNames, want) {
		t.Fatalf("新目标块别名=%v, want %v\n%s", block.AuthorNames, want, fragment.Content)
	}
	if !reflect.DeepEqual(block.Works, []string{"s.jpg"}) || !reflect.DeepEqual(block.Sources, []string{"site-s"}) {
		t.Fatalf("新目标块作品/来源异常：%+v", block)
	}
	for _, b := range authoring.ParseAuthorBlocks(fragment.Content) {
		if len(b.AuthorNames) > 0 && authoring.GenerateAuthorID(b.AuthorNames[0]) == src {
			t.Fatalf("源块未删除:\n%s", fragment.Content)
		}
	}
	entries := uploadEntries(t, e.q)["来源片段.txt"]
	if len(entries) != 1 || entries[0].AuthorID != tgt || !reflect.DeepEqual(entries[0].Names, block.AuthorNames) {
		t.Fatalf("上传条目未改指新目标块：%+v", entries)
	}
}

// TestMergeAuthorsAnonymousTargetFragmentEntries：匿名片段的空文件名是合法片段名，
// 目标块位于匿名片段时上传条目仍必须汇入该片段（不能用空串误判为「无目标块」），
// 否则源片段会保留指向别处目标块的条目并在重导入时误报 409。
func TestMergeAuthorsAnonymousTargetFragmentEntries(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	src := authoring.GenerateAuthorID("匿名源")
	tgt := authoring.GenerateAuthorID("匿名目标")
	seedAuthorRow(t, e.q, tgt, "匿名目标", authoring.AuthorTypeRegular)
	seedAuthorRow(t, e.q, src, "匿名源", authoring.AuthorTypeRegular)
	seedFragments(t, e.q,
		authorattach.Source{Filename: "", Content: "1  匿名目标\n来源  site-t\n作品\nt.jpg\n", ImportedAt: store.FormatTimestamp(testNow)},
		authorattach.Source{Filename: "源.txt", Content: "1  匿名源\n来源  site-s\n作品\ns.jpg\n", ImportedAt: store.FormatTimestamp(testNow)},
	)
	if err := authorattach.SaveUploadEntries(ctx, e.q, testNow, map[string][]authoring.UploadEntry{
		"源.txt": {{
			AuthorID: src, DisplayName: "匿名源", Names: []string{"匿名源"},
			Works: []string{"s.jpg"}, Sources: []string{"site-s"},
		}},
	}); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}

	if _, err := e.svc.MergeAuthors(ctx, src, tgt); err != nil {
		t.Fatalf("合并失败: %v", err)
	}
	anonymous := fragmentByFilename(t, e.q, "")
	block := authorBlockOf(t, anonymous.Content, tgt)
	if want := []string{"匿名目标", "匿名源"}; !reflect.DeepEqual(block.AuthorNames, want) {
		t.Fatalf("匿名目标块别名=%v, want %v\n%s", block.AuthorNames, want, anonymous.Content)
	}
	if !reflect.DeepEqual(block.Works, []string{"t.jpg", "s.jpg"}) || !reflect.DeepEqual(block.Sources, []string{"site-t", "site-s"}) {
		t.Fatalf("匿名目标块作品/来源异常：%+v", block)
	}
	entries := uploadEntries(t, e.q)
	if _, exists := entries["源.txt"]; exists {
		t.Fatalf("源片段条目应汇入匿名目标片段：%+v", entries["源.txt"])
	}
	list := entries[""]
	if len(list) != 1 || list[0].AuthorID != tgt {
		t.Fatalf("匿名片段条目=%+v, want 1 条 target 条目", list)
	}
	if !reflect.DeepEqual(list[0].Names, block.AuthorNames) {
		t.Fatalf("匿名片段条目 Names=%v, want %v", list[0].Names, block.AuthorNames)
	}
	if !reflect.DeepEqual(list[0].Works, []string{"s.jpg"}) || !reflect.DeepEqual(list[0].Sources, []string{"site-s"}) {
		t.Fatalf("匿名片段条目作品/来源=%+v", list[0])
	}
}

// TestMergeAuthorsKeepsTargetProfile：两端都有附属档案时保留目标（不静默
// 覆盖人工资料），源档案随源删除并在结果中标记；target 原已关注时并集不
// 产生实际变化。
func TestMergeAuthorsKeepsTargetProfile(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	src := authoring.GenerateAuthorID("档案源")
	tgt := authoring.GenerateAuthorID("档案目标")
	seedAuthorRow(t, e.q, tgt, "档案目标", authoring.AuthorTypeRegular)
	seedAuthorRow(t, e.q, src, "档案源", authoring.AuthorTypeRegular)
	if _, err := e.q.SetAuthorFollow(ctx, db.SetAuthorFollowParams{Followed: 1, ID: tgt}); err != nil {
		t.Fatalf("预置目标关注失败: %v", err)
	}
	if _, err := e.q.SetAuthorFollow(ctx, db.SetAuthorFollowParams{Followed: 1, ID: src}); err != nil {
		t.Fatalf("预置源关注失败: %v", err)
	}
	seedProfile(t, e.q, tgt, "目标人工资料", 5)
	seedProfile(t, e.q, src, "源人工资料", 2)

	result, err := e.svc.MergeAuthors(ctx, src, tgt)
	if err != nil {
		t.Fatalf("合并失败: %v", err)
	}
	if result.ProfileMoved || !result.SourceProfileDiscarded || result.FollowsMerged {
		t.Fatalf("档案/关注结果=%+v, want profileMoved=false discarded=true followsMerged=false", result)
	}
	targetProfile, err := creatorprofile.Load(ctx, e.q, tgt)
	if err != nil || targetProfile.Nickname != "目标人工资料" || targetProfile.Revision != 5 {
		t.Fatalf("目标档案被覆盖：%+v err=%v", targetProfile, err)
	}
	sourceProfile, err := creatorprofile.Load(ctx, e.q, src)
	if err != nil || !creatorprofile.IsEmpty(sourceProfile) {
		t.Fatalf("源档案未随源删除：%+v err=%v", sourceProfile, err)
	}
}

// TestMergeAuthorsSentinels：COS（源/目标）、同 ID、不存在三种哨兵语义，
// 失败路径零副作用（片段/作者表/关联不变）。
func TestMergeAuthorsSentinels(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	seedLibrary(t, e.q, "lib-1", "normal")
	seedAsset(t, e.q, "asset-1", "lib-1", "f.jpg")
	regularA := authoring.GenerateAuthorID("常规甲")
	regularB := authoring.GenerateAuthorID("常规乙")
	seedAuthorRow(t, e.q, regularA, "常规甲", authoring.AuthorTypeRegular)
	seedAuthorRow(t, e.q, regularB, "常规乙", authoring.AuthorTypeRegular)
	seedAuthorRow(t, e.q, "cos_sentinel", "COS哨兵", authoring.AuthorTypeCos)
	content := "1  常规甲\n来源  site-a\n作品\nf.jpg\n"
	seedFragments(t, e.q, authorattach.Source{
		Filename: "f.txt", Content: content, ImportedAt: store.FormatTimestamp(testNow),
	})
	seedLink(t, e.q, "asset-1", regularA)

	cases := []struct {
		name           string
		source, target string
		want           error
	}{
		{"源是 COS", "cos_sentinel", regularA, authorattach.ErrAuthorNotRegular},
		{"目标是 COS", regularA, "cos_sentinel", authorattach.ErrAuthorNotRegular},
		{"同一身份", regularA, regularA, authorattach.ErrAuthorMergeSelf},
		{"源不存在", "ghost-src", regularA, authorattach.ErrAuthorNotFound},
		{"目标不存在", regularA, "ghost-tgt", authorattach.ErrAuthorNotFound},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := e.svc.MergeAuthors(ctx, tc.source, tc.target); !errors.Is(err, tc.want) {
				t.Fatalf("错误=%v, want %v", err, tc.want)
			}
		})
	}
	if got := fragmentContents(t, e.q); len(got) != 1 || got[0].Content != content {
		t.Fatalf("哨兵失败后片段被改动：%+v", got)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{regularA, regularB, "cos_sentinel"}) {
		t.Fatalf("哨兵失败后作者表被改动：%v", got)
	}
	if got := assetAuthorIDs(t, e.q, "asset-1"); !sameUnordered(got, []string{regularA}) {
		t.Fatalf("哨兵失败后关联被改动：%v", got)
	}
}

// TestMergeAuthorsRollbackOnSourceDeleteFailure：流程末段删除源作者行失败
// 时整体回滚——片段、上传条目、关联溯源、关注、档案、镜像全部保持原样，
// 不出现「表对了但 TXT/档案已变」的半份状态；恢复后重试成功。
func TestMergeAuthorsRollbackOnSourceDeleteFailure(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	seedLibrary(t, e.q, "lib-1", "normal")
	seedAsset(t, e.q, "asset-src", "lib-1", "s.jpg")
	seedAsset(t, e.q, "asset-both", "lib-1", "both.jpg")
	src := authoring.GenerateAuthorID("回滚源")
	tgt := authoring.GenerateAuthorID("回滚目标")
	seedAuthorRow(t, e.q, src, "回滚源", authoring.AuthorTypeRegular)
	seedAuthorRow(t, e.q, tgt, "回滚目标", authoring.AuthorTypeRegular)
	if _, err := e.q.SetAuthorFollow(ctx, db.SetAuthorFollowParams{Followed: 1, ID: src}); err != nil {
		t.Fatalf("预置源关注失败: %v", err)
	}
	seededFragments := []authorattach.Source{
		{Filename: "目标.txt", Content: "1  回滚目标\n来源  site-t\n作品\nt.jpg\n", ImportedAt: store.FormatTimestamp(testNow)},
		{Filename: "源.txt", Content: "1  回滚源\n来源  site-s\n作品\ns.jpg\n", ImportedAt: store.FormatTimestamp(testNow)},
	}
	seedFragments(t, e.q, seededFragments...)
	t1 := store.FormatTimestamp(time.Date(2026, 10, 1, 1, 0, 0, 0, time.UTC))
	t2 := store.FormatTimestamp(time.Date(2026, 10, 2, 1, 0, 0, 0, time.UTC))
	seedLinkAt(t, e.q, "asset-src", src, t1, store.OriginTXT)
	seedLinkAt(t, e.q, "asset-both", src, t2, store.OriginTXT)
	seedLinkAt(t, e.q, "asset-both", tgt, t2, store.OriginImport)
	seededEntries := map[string][]authoring.UploadEntry{
		"目标.txt": {{AuthorID: tgt, DisplayName: "回滚目标", Names: []string{"回滚目标"}, Works: []string{"t.jpg"}}},
		"源.txt":  {{AuthorID: src, DisplayName: "回滚源", Names: []string{"回滚源"}, Works: []string{"s.jpg"}}},
	}
	if err := authorattach.SaveUploadEntries(ctx, e.q, testNow, seededEntries); err != nil {
		t.Fatalf("预置上传条目失败: %v", err)
	}
	seedProfile(t, e.q, src, "回滚资料", 2)
	mirrorPath := filepath.Join(t.TempDir(), "mirror.txt")
	if err := authorattach.SaveMirrorConfig(ctx, e.q, testNow, authorattach.MirrorConfig{
		Path: mirrorPath, FragmentFilename: "目标.txt",
	}); err != nil {
		t.Fatalf("预置镜像配置失败: %v", err)
	}

	e.exec(t, "CREATE TRIGGER boom_merge_author_del BEFORE DELETE ON authors BEGIN SELECT RAISE(ABORT, 'injected authors delete failure'); END")

	if _, err := e.svc.MergeAuthors(ctx, src, tgt); err == nil {
		t.Fatalf("注入源作者行删除失败后期望报错")
	}
	if got := fragmentContents(t, e.q); !reflect.DeepEqual(got, seededFragments) {
		t.Fatalf("失败后片段被改动:\n%+v\nwant:\n%+v", got, seededFragments)
	}
	if got := uploadEntries(t, e.q); !reflect.DeepEqual(got, seededEntries) {
		t.Fatalf("失败后上传条目被改动：%+v", got)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{src, tgt}) {
		t.Fatalf("失败后作者表被改动：%v", got)
	}
	if got := assetAuthorIDs(t, e.q, "asset-src"); !sameUnordered(got, []string{src}) {
		t.Fatalf("失败后 asset-src 关联被改动：%v", got)
	}
	if got := assetAuthorIDs(t, e.q, "asset-both"); !sameUnordered(got, []string{src, tgt}) {
		t.Fatalf("失败后 asset-both 关联被改动：%v", got)
	}
	if row, ok := associationOf(t, e.q, "asset-src", src); !ok || row.Origin != store.OriginTXT || row.CreatedAt.String != t1 {
		t.Fatalf("失败后源关联溯源被改动：%+v", row)
	}
	if row, ok := authorRow(t, e.q, src); !ok || row.Followed != 1 {
		t.Fatalf("失败后源关注被改动：%+v", row)
	}
	if row, ok := authorRow(t, e.q, tgt); !ok || row.Followed != 0 {
		t.Fatalf("失败后目标关注被改动：%+v", row)
	}
	if p, err := creatorprofile.Load(ctx, e.q, src); err != nil || p.Nickname != "回滚资料" {
		t.Fatalf("失败后源档案被改动：%+v err=%v", p, err)
	}
	if p, err := creatorprofile.Load(ctx, e.q, tgt); err != nil || !creatorprofile.IsEmpty(p) {
		t.Fatalf("失败后目标档案被写入：%+v err=%v", p, err)
	}
	if _, err := os.Stat(mirrorPath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("失败路径不得刷新镜像：%v", err)
	}

	e.exec(t, "DROP TRIGGER boom_merge_author_del")
	if _, err := e.svc.MergeAuthors(ctx, src, tgt); err != nil {
		t.Fatalf("恢复后重试合并失败: %v", err)
	}
	if got := allAuthorIDs(t, e.q); !sameUnordered(got, []string{tgt}) {
		t.Fatalf("恢复后作者表=%v, want [%s]", got, tgt)
	}
}
