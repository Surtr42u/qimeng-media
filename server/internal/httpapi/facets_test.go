package httpapi

// facets_test.go：相册四维聚合端点（GET /assets/facets）与 browse 侧 COS
// 分区/作品传参的端到端用例。数据直接经 store 层构造（不经扫描器）——
// 本测试锁的是聚合口径（排自身、COS 隔离、维度互斥排除），不是扫描链路
// （scanner 侧 cos_work 写入由 enrich_test.go 锁定）。
//
// 数据矩阵：
//   常规库：A1 a.jpg 图片（画师A/天使，无出处 →「其他」桶）
//           A2 b.jpg 图片（画师A/天使+黑百合，出处 kemono）
//           A3 c.mp4 视频（无作者无角色，无出处 →「其他」桶）
//   COS 库：C1 作者X/作品P/1.jpg（cos_work=作品P）C2 作者X/2.jpg（NULL 作品）
// 两个库都启用——分区栏的 fixed 三项口径依赖全量计数。作者栏口径（用户
// 决策 2026-09-03，旧版「全部」页 作品行）：常规资产按出处分组（非 COS
// 资产，NULL source =「其他」桶）+ COS 作者，按分区合并；常规作者表行
// （画师A，TXT 导入产物）不进作者栏——authorId 仍作为其他维度过滤生效。

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedFacetFixture 在标准环境上补 COS 库 + 作者/角色/cos_work 关联。
// 返回 COS 库 ID（收藏子集/缺省全部用例按路径取 COS 资产 ID 用）。
func seedFacetFixture(t *testing.T, e *testEnv) string {
	t.Helper()
	ctx := context.Background()
	now := store.FormatTimestamp(time.Now())

	cosRoot := filepath.Join(e.dataDir, "cos-media")
	if err := os.MkdirAll(cosRoot, 0o755); err != nil {
		t.Fatalf("建 COS 库根失败: %v", err)
	}
	cosLib, err := e.q.CreateLibrary(ctx, db.CreateLibraryParams{
		ID: uuid.NewString(), Name: "COS库", RootPath: cosRoot,
		Kind: "cos", CreatedAt: now,
	})
	if err != nil {
		t.Fatalf("建 COS 库失败: %v", err)
	}

	upsert := func(libID, rel, name, mediaType string, cosWork *string) db.Asset {
		t.Helper()
		p := db.UpsertAssetParams{
			AssetID: uuid.NewString(), LibraryID: libID, RelPath: rel,
			FileName: name, MediaType: mediaType, SizeBytes: 10,
			Mtime: now, CreatedAt: now, UpdatedAt: now,
		}
		if cosWork != nil {
			p.CosWork = sql.NullString{String: *cosWork, Valid: true}
		}
		a, err := e.q.UpsertAsset(ctx, p)
		if err != nil {
			t.Fatalf("UpsertAsset %s 失败: %v", rel, err)
		}
		return a
	}

	regAuthorID := authoring.GenerateAuthorID("画师A")
	cosAuthorID := authoring.GenerateCosAuthorID("作者X")
	for _, au := range []db.UpsertAuthorParams{
		{ID: regAuthorID, DisplayName: "画师A", Type: authoring.AuthorTypeRegular, CreatedAt: now},
		{ID: cosAuthorID, DisplayName: "作者X", Type: authoring.AuthorTypeCos, CreatedAt: now},
	} {
		if err := e.q.UpsertAuthor(ctx, au); err != nil {
			t.Fatalf("UpsertAuthor %s 失败: %v", au.DisplayName, err)
		}
	}
	link := func(assetID, authorID string) {
		t.Helper()
		if err := e.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: assetID, AuthorID: authorID}); err != nil {
			t.Fatalf("AddAssetAuthor 失败: %v", err)
		}
	}
	char := func(assetID, name string) {
		t.Helper()
		if err := e.q.AddAssetCharacter(ctx, db.AddAssetCharacterParams{AssetID: assetID, CharacterName: name}); err != nil {
			t.Fatalf("AddAssetCharacter 失败: %v", err)
		}
	}

	// 常规库三资产（newTestEnv 假扫描已入库 a.jpg/b.jpg/c.mp4）：按路径
	// 回写 source（UpsertAsset ON CONFLICT(library_id, rel_path) 只刷新
	// 元数据，asset_id 保持首见——同路径重 Upsert 安全），再补关联。
	setSource := func(rel, mediaType, src string) {
		t.Helper()
		a, err := e.q.UpsertAsset(ctx, db.UpsertAssetParams{
			AssetID: uuid.NewString(), LibraryID: e.libID, RelPath: rel,
			FileName: filepath.Base(rel), MediaType: mediaType, SizeBytes: 10,
			Source: sql.NullString{String: src, Valid: src != ""},
			Mtime:  now, CreatedAt: now, UpdatedAt: now,
		})
		if err != nil {
			t.Fatalf("回写 source %s 失败: %v", rel, err)
		}
		_ = a
	}
	// a.jpg：图片 无出处（进「其他」桶）；b.jpg：图片 出处 kemono；
	// c.mp4：视频 无出处（无作者无角色，假扫描已入库）。
	setSource("a.jpg", "image", "")
	setSource("b.jpg", "image", "kemono")
	setSource("c.mp4", "video", "")
	a1 := e.assetIDByPath(t, "a.jpg")
	a2 := e.assetIDByPath(t, "b.jpg")
	// a.jpg / b.jpg：图片，画师A；角色 天使 / 天使+黑百合
	link(a1, regAuthorID)
	char(a1, "天使")
	link(a2, regAuthorID)
	char(a2, "天使")
	char(a2, "黑百合")
	// c.mp4：视频，无作者无角色（假扫描已入库，无需补）

	// COS 库两资产（直接经 store 入库，模拟扫描写入结果）。
	workP := "作品P"
	c1 := upsert(cosLib.ID, "作者X/作品P/1.jpg", "1.jpg", "image", &workP)
	c2 := upsert(cosLib.ID, "作者X/2.jpg", "2.jpg", "image", nil)
	link(c1.AssetID, cosAuthorID)
	link(c2.AssetID, cosAuthorID)
	return cosLib.ID
}

// assetIDByPath 按库内相对路径取 asset_id（newTestEnv 播种行）。
func (e *testEnv) assetIDByPath(t *testing.T, rel string) string {
	t.Helper()
	a, err := e.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{LibraryID: e.libID, RelPath: rel})
	if err != nil {
		t.Fatalf("GetAssetByPath %s 失败: %v", rel, err)
	}
	return a.AssetID
}

// bucketMap 把胶囊数组转成 key→fileCount（排序不进断言——SQL 的 ORDER BY
// 降序语义已在 facets.sql 侧，测试锁计数与候选集）。
func bucketMap(t *testing.T, raw []byte, dim string) map[string]int {
	t.Helper()
	var resp struct {
		Partitions []struct {
			Key       string `json:"key"`
			FileCount int    `json:"fileCount"`
		} `json:"partitions"`
		Authors []struct {
			Key       string `json:"key"`
			FileCount int    `json:"fileCount"`
		} `json:"authors"`
		Characters []struct {
			Key       string `json:"key"`
			FileCount int    `json:"fileCount"`
		} `json:"characters"`
		Types []struct {
			Key       string `json:"key"`
			FileCount int    `json:"fileCount"`
		} `json:"types"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		t.Fatalf("解析 facets 响应失败: %v", err)
	}
	var rows []struct {
		Key       string `json:"key"`
		FileCount int    `json:"fileCount"`
	}
	switch dim {
	case "partitions":
		rows = resp.Partitions
	case "authors":
		rows = resp.Authors
	case "characters":
		rows = resp.Characters
	case "types":
		rows = resp.Types
	}
	m := make(map[string]int, len(rows))
	for _, r := range rows {
		m[r.Key] = r.FileCount
	}
	return m
}

func assertCounts(t *testing.T, got map[string]int, want map[string]int, what string) {
	t.Helper()
	if len(got) != len(want) {
		t.Errorf("%s: 候选数 %d(%v), want %d(%v)", what, len(got), got, len(want), want)
		return
	}
	for k, w := range want {
		if got[k] != w {
			t.Errorf("%s: %s 计数 = %d, want %d", what, k, got[k], w)
		}
	}
}

func getFacets(t *testing.T, e *testEnv, query string) []byte {
	t.Helper()
	resp := e.do(t, "GET", "/api/v1/assets/facets"+query, "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("facets%s 期望 200，得到 %d", query, resp.StatusCode)
	}
	var raw json.RawMessage
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		t.Fatalf("读取 facets 响应失败: %v", err)
	}
	return raw
}

// TestFacetsPartitionPills：分区栏恒三项全量报告（排自身=忽略 partition
// 参数），regular = all - cos。
func TestFacetsPartitionPills(t *testing.T) {
	env := newTestEnv(t)
	seedFacetFixture(t, env)

	for _, tc := range []struct {
		query string
		want  map[string]int
	}{
		{"", map[string]int{"all": 5, "regular": 3, "cos": 2}},
		{"?partition=all", map[string]int{"all": 5, "regular": 3, "cos": 2}},
		{"?partition=regular", map[string]int{"all": 5, "regular": 3, "cos": 2}},
		{"?partition=cos", map[string]int{"all": 5, "regular": 3, "cos": 2}},
	} {
		got := bucketMap(t, getFacets(t, env, tc.query), "partitions")
		assertCounts(t, got, tc.want, "分区栏"+tc.query)
	}
}

// TestFacetsOtherDims：作者/角色/类型三栏在两种分区下的候选与计数。
// 作者栏 = 常规出处分组（含「其他」桶）∪ COS 作者，按分区合并：常规分区
// 只有出处、COS 分区只有 COS 作者、全部分区两者合并（旧版 groupBySource
// (!isCos) ∪ groupByCosAuthor(isCos) 口径）；常规作者表行（画师A）不进
// 作者栏。COS 分区的角色栏必须是作品名（旧版「COS 角色=作品名」口径）。
func TestFacetsOtherDims(t *testing.T) {
	env := newTestEnv(t)
	seedFacetFixture(t, env)
	regAuthor := authoring.GenerateAuthorID("画师A")
	cosAuthor := authoring.GenerateCosAuthorID("作者X")

	// 缺省（=all 分区）：作者栏 = 出处(a/b/c 无作者角色都参与:其他 2, kemono 1)
	// ∪ COS 作者(2)；角色栏 = 匹配引擎角色名 ∪ COS 作品名（作品P）。
	got := bucketMap(t, getFacets(t, env, ""), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 2, "kemono": 1, cosAuthor: 2}, "缺省作者栏（出处∪COS作者）")
	if _, ok := got[regAuthor]; ok {
		t.Errorf("作者栏不得含常规作者画师A（作者栏=出处+COS 作者）: %v", got)
	}
	got = bucketMap(t, getFacets(t, env, ""), "characters")
	assertCounts(t, got, map[string]int{"天使": 2, "黑百合": 1, "作品P": 1}, "缺省角色栏（角色∪作品）")
	got = bucketMap(t, getFacets(t, env, ""), "types")
	assertCounts(t, got, map[string]int{"all": 5, "image": 4, "video": 1, "animated_image": 0}, "缺省类型栏")

	// regular 分区：作者栏只剩出处分组（COS 作者与作品全部隔离）；角色栏
	// 只剩常规角色。
	got = bucketMap(t, getFacets(t, env, "?partition=regular"), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 2, "kemono": 1}, "regular 作者栏（只出处）")
	got = bucketMap(t, getFacets(t, env, "?partition=regular"), "characters")
	assertCounts(t, got, map[string]int{"天使": 2, "黑百合": 1}, "regular 角色栏")
	got = bucketMap(t, getFacets(t, env, "?partition=regular"), "types")
	assertCounts(t, got, map[string]int{"all": 3, "image": 2, "video": 1, "animated_image": 0}, "regular 类型栏")

	// cos 分区：作者=COS 作者；角色栏=作品名；NULL 作品不列入。
	got = bucketMap(t, getFacets(t, env, "?partition=cos"), "authors")
	assertCounts(t, got, map[string]int{cosAuthor: 2}, "cos 作者栏（只 COS 作者）")
	got = bucketMap(t, getFacets(t, env, "?partition=cos"), "characters")
	assertCounts(t, got, map[string]int{"作品P": 1}, "cos 角色栏（作品名，NULL 不列）")
	got = bucketMap(t, getFacets(t, env, "?partition=cos"), "types")
	assertCounts(t, got, map[string]int{"all": 2, "image": 2, "video": 0, "animated_image": 0}, "cos 类型栏")
}

// TestFacetsExcludeSelf：排自身口径——计某维候选时忽略该维自身选择。
// 作者行 = source 与 authorId 两个参数（排自身时一起忽略）；角色行 =
// character 与 work 两个参数（一起忽略）。
func TestFacetsExcludeSelf(t *testing.T) {
	env := newTestEnv(t)
	seedFacetFixture(t, env)
	regAuthor := authoring.GenerateAuthorID("画师A")
	cosAuthor := authoring.GenerateCosAuthorID("作者X")

	// 选了常规作者画师A（authorId 其他维度过滤仍生效）：作者栏排自身=全量
	// 报告（source 与 authorId 同属作者行，一起忽略）；其余维按画师A 的
	// 资产（a/b.jpg：无视频、无 COS）。
	q := "?authorId=" + regAuthor
	got := bucketMap(t, getFacets(t, env, q), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 2, "kemono": 1, cosAuthor: 2}, "选作者后作者栏（排自身=全量）")
	got = bucketMap(t, getFacets(t, env, q), "characters")
	assertCounts(t, got, map[string]int{"天使": 2, "黑百合": 1}, "选作者后角色栏")
	got = bucketMap(t, getFacets(t, env, q), "types")
	assertCounts(t, got, map[string]int{"all": 2, "image": 2, "video": 0, "animated_image": 0}, "选作者后类型栏")
	got = bucketMap(t, getFacets(t, env, q), "partitions")
	assertCounts(t, got, map[string]int{"all": 2, "regular": 2, "cos": 0}, "选作者后分区栏")

	// 选了出处 kemono（作者行内 pill）：作者栏排自身=忽略 source → 全量；
	// 其余维按 kemono 资产（b.jpg 一个）。
	q = "?source=kemono"
	got = bucketMap(t, getFacets(t, env, q), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 2, "kemono": 1, cosAuthor: 2}, "选出处分组后作者栏（排自身=全量）")
	got = bucketMap(t, getFacets(t, env, q), "characters")
	assertCounts(t, got, map[string]int{"天使": 1, "黑百合": 1}, "选出处后角色栏（只 kemono 资产）")
	got = bucketMap(t, getFacets(t, env, q), "types")
	assertCounts(t, got, map[string]int{"all": 1, "image": 1, "video": 0, "animated_image": 0}, "选出处后类型栏")

	// 选了视频：类型栏全量报告（排自身=忽略 mediaType）；作者栏 = 视频资产
	// 的出处（c.mp4 无出处 →「其他」，COS 资产不占——c.mp4 无 COS 关联）；
	// 角色栏空（视频无角色）。
	got = bucketMap(t, getFacets(t, env, "?mediaType=video"), "types")
	assertCounts(t, got, map[string]int{"all": 5, "image": 4, "video": 1, "animated_image": 0}, "选类型后类型栏（排自身=全量）")
	got = bucketMap(t, getFacets(t, env, "?mediaType=video"), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 1}, "选类型后作者栏（视频无出处→其他，不含 COS）")
	got = bucketMap(t, getFacets(t, env, "?mediaType=video"), "characters")
	assertCounts(t, got, map[string]int{}, "选类型后角色栏（视频无角色）")

	// 选了角色天使：角色栏排自身（character 与 work 同属角色行，一起忽略）
	// → 计数不变；作者栏 = 天使资产出处（a.jpg 其他 / b.jpg kemono 各 1）。
	q = "?character=%E5%A4%A9%E4%BD%BF"
	got = bucketMap(t, getFacets(t, env, q), "characters")
	assertCounts(t, got, map[string]int{"天使": 2, "黑百合": 1, "作品P": 1}, "选角色后角色栏（排自身）")
	got = bucketMap(t, getFacets(t, env, q), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 1, "kemono": 1}, "选角色后作者栏")
}

// TestAssetsCosPartitionParams：browse 侧三态开关与作品筛选（/assets 的
// cosOnly/work 新参数；cosOnly 与 includeCos 同真时 cosOnly 优先）。
func TestAssetsCosPartitionParams(t *testing.T) {
	env := newTestEnv(t)
	seedFacetFixture(t, env)

	fetchIDs := func(query string) map[string]bool {
		t.Helper()
		resp := e_doAssets(t, env, query)
		defer closeBody(resp)
		var page struct {
			Items []struct {
				ID string `json:"id"`
			} `json:"items"`
			TotalMatched *int `json:"totalMatched"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&page); err != nil {
			t.Fatalf("解析 assets 响应失败: %v", err)
		}
		ids := make(map[string]bool, len(page.Items))
		for _, it := range page.Items {
			ids[it.ID] = true
		}
		if page.TotalMatched == nil || *page.TotalMatched != len(ids) {
			t.Errorf("%s: totalMatched=%v, want %d", query, page.TotalMatched, len(ids))
		}
		return ids
	}

	regA := env.assetIDByPath(t, "a.jpg")
	regB := env.assetIDByPath(t, "b.jpg")
	regC := env.assetIDByPath(t, "c.mp4")
	// COS 两资产 ID 未知（upsert 内部生成）——按集合差断言。

	// 默认：常规三分（COS 隔离口径不变）。
	got := fetchIDs("?limit=50")
	if len(got) != 3 || !got[regA] || !got[regB] || !got[regC] {
		t.Errorf("默认列表=%v, want 3 条常规资产", got)
	}
	// includeCos：全量五条。
	if got = fetchIDs("?limit=50&includeCos=true"); len(got) != 5 {
		t.Errorf("includeCos 列表=%v, want 5 条", got)
	}
	// cosOnly：只要 COS 两条。
	if got = fetchIDs("?limit=50&cosOnly=true"); len(got) != 2 {
		t.Errorf("cosOnly 列表=%v, want 2 条 COS 资产", got)
	}
	// cosOnly 与 includeCos 同真：cosOnly 优先（协议注释口径）。
	if got = fetchIDs("?limit=50&includeCos=true&cosOnly=true"); len(got) != 2 {
		t.Errorf("cosOnly+includeCos 列表=%v, want 2 条（cosOnly 优先）", got)
	}
	// work 作品筛选：与 includeCos 组合只中作品 P 一条。
	if got = fetchIDs("?limit=50&includeCos=true&work=" + percentEncode("作品P")); len(got) != 1 {
		t.Errorf("work=作品P 列表=%v, want 1 条", got)
	}
}

// TestFacetsFavoriteHistorySubsets：子集约束（2026-09-05 S-1 批次）——
// favorite=1 / history=1 对全部四维（含分区栏）统一收窄统计口径；与
// mediaType 组合叠加；不带子集参数时行为与既有口径全同（回归由
// TestFacetsPartitionPills / TestFacetsOtherDims / TestFacetsExcludeSelf
// 三个既有用例持续锁定，本用例只补子集路径）。
//
// 数据基座 = seedFacetFixture 五资产，叠加行为数据：
//
//	收藏：a.jpg（常规图片）、C1（COS 图片，作品P）
//	历史：b.jpg（常规图片）、c.mp4（常规视频）、C2（COS 图片，NULL 作品）
func TestFacetsFavoriteHistorySubsets(t *testing.T) {
	env := newTestEnv(t)
	cosLibID := seedFacetFixture(t, env)
	ctx := context.Background()
	now := store.FormatTimestamp(time.Now())

	regA := env.assetIDByPath(t, "a.jpg")
	regB := env.assetIDByPath(t, "b.jpg")
	regC := env.assetIDByPath(t, "c.mp4")
	c1Row, err := env.q.GetAssetByPath(ctx, db.GetAssetByPathParams{LibraryID: cosLibID, RelPath: "作者X/作品P/1.jpg"})
	if err != nil {
		t.Fatalf("取 C1 失败: %v", err)
	}
	c2Row, err := env.q.GetAssetByPath(ctx, db.GetAssetByPathParams{LibraryID: cosLibID, RelPath: "作者X/2.jpg"})
	if err != nil {
		t.Fatalf("取 C2 失败: %v", err)
	}

	addFav := func(assetID string) {
		t.Helper()
		if _, err := env.q.AddFavorite(ctx, db.AddFavoriteParams{AssetID: assetID, CreatedAt: now}); err != nil {
			t.Fatalf("写收藏失败: %v", err)
		}
	}
	addFav(regA)
	addFav(c1Row.AssetID)
	reportOpenAt(t, env, regB, "2026-08-22T10:00:00Z", "fs-h1")
	reportOpenAt(t, env, regC, "2026-08-22T11:00:00Z", "fs-h2")
	reportOpenAt(t, env, c2Row.AssetID, "2026-08-22T12:00:00Z", "fs-h3")

	cosAuthor := authoring.GenerateCosAuthorID("作者X")

	// favorite=1：收藏子集 = a.jpg（常规/其他桶/天使/图片）+ C1（COS/作者X/作品P/图片）。
	got := bucketMap(t, getFacets(t, env, "?favorite=1"), "partitions")
	assertCounts(t, got, map[string]int{"all": 2, "regular": 1, "cos": 1}, "favorite=1 分区栏")
	got = bucketMap(t, getFacets(t, env, "?favorite=1"), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 1, cosAuthor: 1}, "favorite=1 作者栏")
	got = bucketMap(t, getFacets(t, env, "?favorite=1"), "characters")
	assertCounts(t, got, map[string]int{"天使": 1, "作品P": 1}, "favorite=1 角色栏")
	got = bucketMap(t, getFacets(t, env, "?favorite=1"), "types")
	assertCounts(t, got, map[string]int{"all": 2, "image": 2, "video": 0, "animated_image": 0}, "favorite=1 类型栏")

	// history=1：历史子集 = b.jpg（kemono/天使+黑百合/图片）+ c.mp4（其他/视频）
	// + C2（作者X/NULL 作品不列/图片）。
	got = bucketMap(t, getFacets(t, env, "?history=1"), "partitions")
	assertCounts(t, got, map[string]int{"all": 3, "regular": 2, "cos": 1}, "history=1 分区栏")
	got = bucketMap(t, getFacets(t, env, "?history=1"), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 1, "kemono": 1, cosAuthor: 1}, "history=1 作者栏")
	got = bucketMap(t, getFacets(t, env, "?history=1"), "characters")
	assertCounts(t, got, map[string]int{"天使": 1, "黑百合": 1}, "history=1 角色栏（C2 无作品不列）")
	got = bucketMap(t, getFacets(t, env, "?history=1"), "types")
	assertCounts(t, got, map[string]int{"all": 3, "image": 2, "video": 1, "animated_image": 0}, "history=1 类型栏")

	// 与 mediaType 组合：分区/作者栏按历史∩视频收窄；类型栏排自身=忽略
	// mediaType（mediaType 正是类型行自身维度），保持历史子集全量计数。
	got = bucketMap(t, getFacets(t, env, "?history=1&mediaType=video"), "partitions")
	assertCounts(t, got, map[string]int{"all": 1, "regular": 1, "cos": 0}, "history=1&video 分区栏")
	got = bucketMap(t, getFacets(t, env, "?history=1&mediaType=video"), "authors")
	assertCounts(t, got, map[string]int{sourceOtherLabel: 1}, "history=1&video 作者栏")
	got = bucketMap(t, getFacets(t, env, "?history=1&mediaType=video"), "characters")
	assertCounts(t, got, map[string]int{}, "history=1&video 角色栏（c.mp4 无角色）")
	got = bucketMap(t, getFacets(t, env, "?history=1&mediaType=video"), "types")
	assertCounts(t, got, map[string]int{"all": 3, "image": 2, "video": 1, "animated_image": 0}, "history=1&video 类型栏（排自身=历史子集全量）")
}

// TestAssetsFavoriteDefaultAll：收藏流缺省「全部」（2026-09-05 用户拍板
// 1A）——favorite=true 且未显式传分区参数 → 含 COS 收藏；显式
// includeCos=false 仍切常规；cosOnly 优先逻辑不变；不带 favorite 维持
// 缺省排除 COS（防回归）。
func TestAssetsFavoriteDefaultAll(t *testing.T) {
	env := newTestEnv(t)
	cosLibID := seedFacetFixture(t, env)
	ctx := context.Background()
	now := store.FormatTimestamp(time.Now())

	regA := env.assetIDByPath(t, "a.jpg")
	c1Row, err := env.q.GetAssetByPath(ctx, db.GetAssetByPathParams{LibraryID: cosLibID, RelPath: "作者X/作品P/1.jpg"})
	if err != nil {
		t.Fatalf("取 C1 失败: %v", err)
	}
	for _, id := range []string{regA, c1Row.AssetID} {
		if _, err := env.q.AddFavorite(ctx, db.AddFavoriteParams{AssetID: id, CreatedAt: now}); err != nil {
			t.Fatalf("写收藏失败: %v", err)
		}
	}

	fetch := func(query string) map[string]bool {
		t.Helper()
		resp := e_doAssets(t, env, query)
		defer closeBody(resp)
		var page struct {
			Items []struct {
				ID       string `json:"id"`
				FileName string `json:"fileName"`
			} `json:"items"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&page); err != nil {
			t.Fatalf("解析 assets 响应失败: %v", err)
		}
		got := make(map[string]bool, len(page.Items))
		for _, it := range page.Items {
			got[it.FileName] = true
		}
		return got
	}
	// favorite=1 缺省：常规∪COS 收藏全出（1A 拍板口径）。
	got := fetch("?favorite=true&limit=50")
	if len(got) != 2 || !got["a.jpg"] || !got["1.jpg"] {
		t.Errorf("favorite=true 缺省应含 COS 收藏共 2 条，得到 %v", got)
	}
	// favorite=1&includeCos=false：显式切常规，只剩 a.jpg。
	got = fetch("?favorite=true&includeCos=false&limit=50")
	if len(got) != 1 || !got["a.jpg"] {
		t.Errorf("favorite=true&includeCos=false 应只含 a.jpg，得到 %v", got)
	}
	// favorite=1&cosOnly=true：cosOnly 优先，只剩 C1。
	got = fetch("?favorite=true&cosOnly=true&limit=50")
	if len(got) != 1 || !got["1.jpg"] {
		t.Errorf("favorite=true&cosOnly=true 应只含 1.jpg，得到 %v", got)
	}
	// 不带 favorite：维持缺省排除 COS（三分，防回归）。
	got = fetch("?limit=50")
	if len(got) != 3 || got["1.jpg"] || got["2.jpg"] {
		t.Errorf("不带 favorite 应维持常规三分，得到 %v", got)
	}
}

// e_doAssets GET /assets 并返回响应（游标分页首页足够——limit=50 全量）。
func e_doAssets(t *testing.T, e *testEnv, query string) *http.Response {
	t.Helper()
	return e.do(t, "GET", "/api/v1/assets"+query, "")
}

// percentEncode UTF-8 查询值（测试只此一处用，不引依赖）。
func percentEncode(s string) string {
	var out string
	for _, b := range []byte(s) {
		out += "%" + hexByte(b)
	}
	return out
}

func hexByte(b byte) string {
	const digits = "0123456789ABCDEF"
	return string([]byte{digits[b>>4], digits[b&0x0F]})
}
