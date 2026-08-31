package scanner

// 作者体系富化挂接测试（M3）：normal 库 SourceMatcher 出处/角色落库、
// COS 库 cos_ 作者与映射、改名重入库后重匹配、自定义出处装载。
// 文件名一律用内置检索表真实组名/角色名（source_groups_data.go 已确认：
// 守望先锋组含「天使」「黑百合」）。

import (
	"context"
	"os"
	"testing"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store/db"
)

// characterNames 读某资产的角色规范名（经生成的查询）。
func characterNames(t *testing.T, e *testEnv, assetID string) []string {
	t.Helper()
	names, err := e.q.ListAssetCharacterNames(context.Background(), assetID)
	if err != nil {
		t.Fatalf("ListAssetCharacterNames 失败: %v", err)
	}
	return names
}

// TestScanEnrichesSourceAndCharacters：normal 库扫描时按文件名匹配出处
// 与角色并落库；未命中文件 source 为 NULL、无角色行。
func TestScanEnrichesSourceAndCharacters(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "守望先锋_天使 4.jpg", 100)
	env.writeFile(t, "randomfile.jpg", 50)

	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}
	hit := env.assetByPath(t, "守望先锋_天使 4.jpg")
	if !hit.Source.Valid || hit.Source.String != "守望先锋" {
		t.Errorf("source=%v, want 守望先锋", hit.Source)
	}
	if got := characterNames(t, env, hit.AssetID); len(got) != 1 || got[0] != "天使" {
		t.Errorf("characters=%v, want [天使]", got)
	}

	miss := env.assetByPath(t, "randomfile.jpg")
	if miss.Source.Valid {
		t.Errorf("未命中文件 source=%v, want NULL", miss.Source)
	}
	if got := characterNames(t, env, miss.AssetID); len(got) != 0 {
		t.Errorf("未命中文件角色=%v, want 空", got)
	}
}

// TestScanCosLibraryCreatesAuthors：cos 库按目录结构（结构 1 作者/文件、
// 结构 2 作者/作品/文件）建 cos_ 作者并关联；source 保持为空（隔离口径，
// 不做 SourceMatcher 匹配——含内置表组名也不命中）。
func TestScanCosLibraryCreatesAuthors(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	// Scan 收的是 db.Library 值拷贝，DB 行与测试内拷贝同步置 cos。
	if _, err := env.conn.Exec(`UPDATE libraries SET kind = 'cos' WHERE id = ?`, env.lib.ID); err != nil {
		t.Fatalf("置 cos kind 失败: %v", err)
	}
	env.lib.Kind = LibraryKindCos
	env.writeFile(t, "水淼Aqua/不知火舞/1.jpg", 100) // 结构 2
	env.writeFile(t, "作者C/2.mp4", 100)         // 结构 1（含内置组名的作者目录，验证不触发 matcher）
	env.writeFile(t, "守望先锋_天使/3.jpg", 100)     // 结构 1：文件名含组名，仍不得匹配

	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}

	a1 := env.assetByPath(t, "水淼Aqua/不知火舞/1.jpg")
	a2 := env.assetByPath(t, "作者C/2.mp4")
	a3 := env.assetByPath(t, "守望先锋_天使/3.jpg")

	for _, tc := range []struct {
		name   string
		dir    string
		asset  db.Asset
		wantID string
	}{
		{"结构2", "水淼Aqua", a1, authoring.GenerateCosAuthorID("水淼Aqua")},
		{"结构1", "作者C", a2, authoring.GenerateCosAuthorID("作者C")},
		{"组名目录", "守望先锋_天使", a3, authoring.GenerateCosAuthorID("守望先锋_天使")},
	} {
		if got := tc.asset.Source; got.Valid {
			t.Errorf("%s: cos 文件 source=%v, 必须为 NULL（隔离口径）", tc.name, got)
		}
		if got := characterNames(t, env, tc.asset.AssetID); len(got) != 0 {
			t.Errorf("%s: cos 文件角色=%v, want 空", tc.name, got)
		}
		authorRefs, err := env.q.ListAssetAuthorRefs(context.Background(), tc.asset.AssetID)
		if err != nil {
			t.Fatalf("ListAssetAuthorRefs 失败: %v", err)
		}
		if len(authorRefs) != 1 || authorRefs[0].ID != tc.wantID || authorRefs[0].Type != authoring.AuthorTypeCos {
			t.Errorf("%s: 关联作者=%+v, want [{%s cos}]", tc.name, authorRefs, tc.wantID)
		}
	}
}

// TestRenameWithMtimeRecomputesEnrichment：改名 + mtime 变化 → 不走移动合并、
// 重 ingest → source/角色按新文件名重算（覆盖语义，enrich.go 文件头注释）。
func TestRenameWithMtimeRecomputesEnrichment(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "守望先锋_天使.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("首扫失败: %v", err)
	}
	old := env.assetByPath(t, "守望先锋_天使.jpg")
	if !old.Source.Valid || old.Source.String != "守望先锋" {
		t.Fatalf("首扫 source=%v, want 守望先锋", old.Source)
	}

	if err := os.Rename(env.abs("守望先锋_天使.jpg"), env.abs("守望先锋_黑百合.jpg")); err != nil {
		t.Fatalf("rename 失败: %v", err)
	}
	// 拨动 mtime：让改名后的文件走「重入库」而非移动合并（合并条件要求
	// mtime 一致），这正是富化重算的触发路径。
	future := time.Now().Add(2 * time.Hour)
	if err := os.Chtimes(env.abs("守望先锋_黑百合.jpg"), future, future); err != nil {
		t.Fatalf("Chtimes 失败: %v", err)
	}
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("重扫失败: %v", err)
	}

	if env.assetCount(t) != 1 {
		t.Fatalf("重扫后库内 %d 条, want 1", env.assetCount(t))
	}
	renamed := env.assetByPath(t, "守望先锋_黑百合.jpg")
	if renamed.Source.String != "守望先锋" {
		t.Errorf("改名后 source=%q, want 守望先锋", renamed.Source.String)
	}
	if got := characterNames(t, env, renamed.AssetID); len(got) != 1 || got[0] != "黑百合" {
		t.Errorf("改名后角色=%v, want [黑百合]（按新文件名重算）", got)
	}
}

// TestCustomSourcesLoadedFromSettings：Scanner 构造时从 kv_settings 装载
// 用户自定义出处，自定义分区名文件命中自定义出处（无角色表，角色为空）。
func TestCustomSourcesLoadedFromSettings(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	if err := env.q.UpsertSetting(context.Background(), db.UpsertSettingParams{
		Key:       authoring.SettingKeyCustomSources,
		Value:     `["我的分区"]`,
		UpdatedAt: "2026-08-30T00:00:00.000Z",
	}); err != nil {
		t.Fatalf("写自定义出处失败: %v", err)
	}

	// 重新构造扫描器（装载发生在 New）。
	env2 := &testEnv{s: New(env.q, env.bus, nil, ""), q: env.q, bus: env.bus, lib: env.lib, conn: env.conn}
	env2.s.probe = probe.call
	env2.writeFile(t, "我的分区_某角色.jpg", 100)
	if _, err := env2.s.Scan(context.Background(), env2.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}
	a := env2.assetByPath(t, "我的分区_某角色.jpg")
	if !a.Source.Valid || a.Source.String != "我的分区" {
		t.Errorf("自定义出处 source=%v, want 我的分区", a.Source)
	}
	if got := characterNames(t, env, a.AssetID); len(got) != 0 {
		t.Errorf("自定义出处无角色表, 角色=%v, want 空", got)
	}
}

// TestEnrichAsset：单资产重富化——API 移动/重命名后库行 file_name 已变而
// size+mtime 未变（不会重 ingest），EnrichAsset 按新名重算 source/角色；
// cos 库直接跳过（作者关联走目录维度）。
func TestEnrichAsset(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "守望先锋_天使.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}
	a := env.assetByPath(t, "守望先锋_天使.jpg")

	// 模拟 filing 改名：MoveAssetPath 更新库行（文件系统侧对本测试无影响）
	if _, err := env.q.MoveAssetPath(context.Background(), db.MoveAssetPathParams{
		RelPath: "守望先锋_黑百合.jpg", FileName: "守望先锋_黑百合.jpg",
		UpdatedAt: "2026-08-30T00:00:00.000Z", AssetID: a.AssetID,
	}); err != nil {
		t.Fatalf("MoveAssetPath 失败: %v", err)
	}
	if err := env.s.EnrichAsset(context.Background(), env.lib.ID, a.AssetID); err != nil {
		t.Fatalf("EnrichAsset 失败: %v", err)
	}
	renamed, err := env.q.GetAsset(context.Background(), a.AssetID)
	if err != nil {
		t.Fatalf("GetAsset 失败: %v", err)
	}
	if renamed.Source.String != "守望先锋" {
		t.Errorf("重算后 source=%q, want 守望先锋", renamed.Source.String)
	}
	if got := characterNames(t, env, a.AssetID); len(got) != 1 || got[0] != "黑百合" {
		t.Errorf("重算后角色=%v, want [黑百合]（按新文件名重算）", got)
	}
}

// TestEnrichAssetCosLibraryRecomputesAuthor：cos 库文件经 API 移动/重命名
// （库行 rel 变、size+mtime 不变）后 EnrichAsset 按新 rel 首段目录重算
// 作者关联——旧作者被清掉、新作者建立。
func TestEnrichAssetCosLibraryRecomputesAuthor(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	if _, err := env.conn.Exec(`UPDATE libraries SET kind = 'cos' WHERE id = ?`, env.lib.ID); err != nil {
		t.Fatalf("置 cos kind 失败: %v", err)
	}
	env.lib.Kind = LibraryKindCos
	env.writeFile(t, "作者A/作品1/1.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}
	a := env.assetByPath(t, "作者A/作品1/1.jpg")
	oldAuthor := authoring.GenerateCosAuthorID("作者A")

	// 模拟 filing 移动：库行 rel 改到另一作者目录（文件系统侧不变）。
	if _, err := env.q.MoveAssetPath(context.Background(), db.MoveAssetPathParams{
		RelPath: "作者B/作品2/1.jpg", FileName: "1.jpg",
		UpdatedAt: "2026-08-30T00:00:00.000Z", AssetID: a.AssetID,
	}); err != nil {
		t.Fatalf("MoveAssetPath 失败: %v", err)
	}
	if err := env.s.EnrichAsset(context.Background(), env.lib.ID, a.AssetID); err != nil {
		t.Fatalf("EnrichAsset 失败: %v", err)
	}

	refs, err := env.q.ListAssetAuthorRefs(context.Background(), a.AssetID)
	if err != nil {
		t.Fatalf("ListAssetAuthorRefs 失败: %v", err)
	}
	newAuthor := authoring.GenerateCosAuthorID("作者B")
	if len(refs) != 1 || refs[0].ID != newAuthor {
		t.Errorf("移动后关联作者=%+v, want [{%s}]（旧作者 %s 应被清理）", refs, newAuthor, oldAuthor)
	}
}

// TestScanMoveMergeCosLibraryRecomputesAuthor：cos 库作者目录整体改名
// （旧目录消失 + 新目录出现，size+mtime 一致 → 移动合并）后，重扫按新
// 首段目录重算作者关联，旧作者行随收尾清理消失。
func TestScanMoveMergeCosLibraryRecomputesAuthor(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	if _, err := env.conn.Exec(`UPDATE libraries SET kind = 'cos' WHERE id = ?`, env.lib.ID); err != nil {
		t.Fatalf("置 cos kind 失败: %v", err)
	}
	env.lib.Kind = LibraryKindCos
	env.writeFile(t, "作者A/作品1/1.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("首扫失败: %v", err)
	}
	a := env.assetByPath(t, "作者A/作品1/1.jpg")
	oldAuthor := authoring.GenerateCosAuthorID("作者A")

	// 文件系统侧改作者目录名（文件 mtime 不变 → 合并而非重 ingest）。
	if err := os.Rename(env.abs("作者A"), env.abs("作者R")); err != nil {
		t.Fatalf("改目录名失败: %v", err)
	}
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("重扫失败: %v", err)
	}

	renamed := env.assetByPath(t, "作者R/作品1/1.jpg")
	if renamed.AssetID != a.AssetID {
		t.Errorf("移动合并应保持身份：%s → %s", a.AssetID, renamed.AssetID)
	}
	refs, err := env.q.ListAssetAuthorRefs(context.Background(), a.AssetID)
	if err != nil {
		t.Fatalf("ListAssetAuthorRefs 失败: %v", err)
	}
	want := authoring.GenerateCosAuthorID("作者R")
	if len(refs) != 1 || refs[0].ID != want {
		t.Errorf("目录改名后关联作者=%+v, want [{%s}]", refs, want)
	}
	var orphans int
	if err := env.conn.QueryRow(`SELECT count(*) FROM authors WHERE id = ?`, oldAuthor).Scan(&orphans); err != nil {
		t.Fatalf("查询旧作者失败: %v", err)
	}
	if orphans != 0 {
		t.Errorf("旧作者 %s 应被孤立清理删除，实际存在", oldAuthor)
	}
}

// TestScanCleansOrphanCosAuthor：cos 库作者目录最后一个文件被删除后重扫，
// 孤立 COS 作者行随收尾清理消失（DOMAIN_RULES §6 扫描后清理语义）。
func TestScanCleansOrphanCosAuthor(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	if _, err := env.conn.Exec(`UPDATE libraries SET kind = 'cos' WHERE id = ?`, env.lib.ID); err != nil {
		t.Fatalf("置 cos kind 失败: %v", err)
	}
	env.lib.Kind = LibraryKindCos
	env.writeFile(t, "作者A/1.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("首扫失败: %v", err)
	}
	authorID := authoring.GenerateCosAuthorID("作者A")
	var before int
	if err := env.conn.QueryRow(`SELECT count(*) FROM authors WHERE id = ?`, authorID).Scan(&before); err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	if before != 1 {
		t.Fatalf("首扫后作者应存在，实际 %d", before)
	}
	if err := os.Remove(env.abs("作者A/1.jpg")); err != nil {
		t.Fatalf("删文件失败: %v", err)
	}
	if err := os.Remove(env.abs("作者A")); err != nil {
		t.Fatalf("删目录失败: %v", err)
	}
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("重扫失败: %v", err)
	}
	if env.assetCount(t) != 0 {
		t.Fatalf("文件消失后资产应清零，实际 %d", env.assetCount(t))
	}
	var after int
	if err := env.conn.QueryRow(`SELECT count(*) FROM authors WHERE id = ?`, authorID).Scan(&after); err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	if after != 0 {
		t.Errorf("孤立作者应被清理，实际仍存在 %d", after)
	}
}

// TestRecomputeEnrichmentAfterCustomSources：自定义出处变更后，已入库资产
// size+mtime 未变（全量扫描只会跳过），必须 RecomputeEnrichment 显式重算
// 才能把新名字传导到存量 source 列。
func TestRecomputeEnrichmentAfterCustomSources(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "我的分区_角色A.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}
	a := env.assetByPath(t, "我的分区_角色A.jpg")
	if a.Source.Valid {
		t.Fatalf("首扫不应命中（自定义出处未配置，内置表无此名）：source=%v", a.Source)
	}

	// 运行期替换（模拟 PUT /sources/custom 后的同步），再全库重算。
	env.s.UpdateCustomSources(context.Background(), []string{"我的分区"})
	if err := env.s.RecomputeEnrichment(context.Background(), env.lib.ID); err != nil {
		t.Fatalf("RecomputeEnrichment 失败: %v", err)
	}
	after := env.assetByPath(t, "我的分区_角色A.jpg")
	if !after.Source.Valid || after.Source.String != "我的分区" {
		t.Errorf("重算后 source=%v, want 我的分区", after.Source)
	}
}
