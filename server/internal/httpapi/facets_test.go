package httpapi

// facets_test.go：相册四维聚合端点（GET /assets/facets）与 browse 侧 COS
// 分区/作品传参的端到端用例。数据直接经 store 层构造（不经扫描器）——
// 本测试锁的是聚合口径（排自身、COS 隔离、维度互斥排除），不是扫描链路
// （scanner 侧 cos_work 写入由 enrich_test.go 锁定）。
//
// 数据矩阵：
//   常规库：A1 a.jpg 图片（画师A/天使）A2 b.jpg 图片（画师A/天使+黑百合）
//           A3 c.mp4 视频（无作者无角色）
//   COS 库：C1 作者X/作品P/1.jpg（cos_work=作品P）C2 作者X/2.jpg（NULL 作品）
// 两个库都启用——分区栏的 fixed 三项口径依赖全量计数。

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
// 返回 COS 库 ID（当前断言不需要，留作扩展）。
func seedFacetFixture(t *testing.T, e *testEnv) {
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

	// 常规库三资产（newTestEnv 已播种 a.jpg/b.jpg/c.mp4 的库行，这里覆盖
	// 富化列——直接再 Upsert 一遍同路径行会撞身份；改为用假扫描已入库的
	// 现有行，按路径取回后补关联）。
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

// TestFacetsOtherDims：作者/角色/类型三栏在两种分区下的候选与计数；
// COS 分区的角色栏必须是作品名（旧版「COS 角色=作品名」口径）。
func TestFacetsOtherDims(t *testing.T) {
	env := newTestEnv(t)
	seedFacetFixture(t, env)
	regAuthor := authoring.GenerateAuthorID("画师A")
	cosAuthor := authoring.GenerateCosAuthorID("作者X")

	// 缺省（=all 分区）：作者双体系一起列出；角色=匹配引擎角色名。
	got := bucketMap(t, getFacets(t, env, ""), "authors")
	assertCounts(t, got, map[string]int{cosAuthor: 2, regAuthor: 2}, "缺省作者栏")
	got = bucketMap(t, getFacets(t, env, ""), "characters")
	assertCounts(t, got, map[string]int{"天使": 2, "黑百合": 1}, "缺省角色栏")
	got = bucketMap(t, getFacets(t, env, ""), "types")
	assertCounts(t, got, map[string]int{"all": 5, "image": 4, "video": 1, "animated_image": 0}, "缺省类型栏")

	// regular 分区：只剩常规作者/常规资产。
	got = bucketMap(t, getFacets(t, env, "?partition=regular"), "authors")
	assertCounts(t, got, map[string]int{regAuthor: 2}, "regular 作者栏")
	got = bucketMap(t, getFacets(t, env, "?partition=regular"), "characters")
	assertCounts(t, got, map[string]int{"天使": 2, "黑百合": 1}, "regular 角色栏")
	got = bucketMap(t, getFacets(t, env, "?partition=regular"), "types")
	assertCounts(t, got, map[string]int{"all": 3, "image": 2, "video": 1, "animated_image": 0}, "regular 类型栏")

	// cos 分区：作者=COS 作者；角色栏=作品名；NULL 作品不列入。
	got = bucketMap(t, getFacets(t, env, "?partition=cos"), "authors")
	assertCounts(t, got, map[string]int{cosAuthor: 2}, "cos 作者栏")
	got = bucketMap(t, getFacets(t, env, "?partition=cos"), "characters")
	assertCounts(t, got, map[string]int{"作品P": 1}, "cos 角色栏（作品名，NULL 不列）")
	got = bucketMap(t, getFacets(t, env, "?partition=cos"), "types")
	assertCounts(t, got, map[string]int{"all": 2, "image": 2, "video": 0, "animated_image": 0}, "cos 类型栏")
}

// TestFacetsExcludeSelf：排自身口径——计某维候选时忽略该维自身选择。
func TestFacetsExcludeSelf(t *testing.T) {
	env := newTestEnv(t)
	seedFacetFixture(t, env)
	regAuthor := authoring.GenerateAuthorID("画师A")
	cosAuthor := authoring.GenerateCosAuthorID("作者X")

	// 选了画师A：作者栏全量报告（排自身=忽略 authorId）；其余维按画师A
	// 的资产算（无视频、无 COS）。
	q := "?authorId=" + regAuthor
	got := bucketMap(t, getFacets(t, env, q), "authors")
	assertCounts(t, got, map[string]int{regAuthor: 2, cosAuthor: 2}, "选作者后作者栏（排自身=全量）")
	got = bucketMap(t, getFacets(t, env, q), "types")
	assertCounts(t, got, map[string]int{"all": 2, "image": 2, "video": 0, "animated_image": 0}, "选作者后类型栏")
	got = bucketMap(t, getFacets(t, env, q), "partitions")
	assertCounts(t, got, map[string]int{"all": 2, "regular": 2, "cos": 0}, "选作者后分区栏")

	// 选了视频：类型栏全量报告（排自身=忽略 mediaType）；视频无作者无
	// 角色 → 作者/角色栏空。
	got = bucketMap(t, getFacets(t, env, "?mediaType=video"), "types")
	assertCounts(t, got, map[string]int{"all": 5, "image": 4, "video": 1, "animated_image": 0}, "选类型后类型栏（排自身=全量）")
	got = bucketMap(t, getFacets(t, env, "?mediaType=video"), "authors")
	assertCounts(t, got, map[string]int{}, "选类型后作者栏（视频无作者）")
	got = bucketMap(t, getFacets(t, env, "?mediaType=video"), "characters")
	assertCounts(t, got, map[string]int{}, "选类型后角色栏（视频无角色）")

	// 选了角色天使：角色栏计数不变；作者栏=含天使资产的作者。
	got = bucketMap(t, getFacets(t, env, "?character=%E5%A4%A9%E4%BD%BF"), "characters")
	assertCounts(t, got, map[string]int{"天使": 2, "黑百合": 1}, "选角色后角色栏（排自身）")
	got = bucketMap(t, getFacets(t, env, "?character=%E5%A4%A9%E4%BD%BF"), "authors")
	assertCounts(t, got, map[string]int{regAuthor: 2}, "选角色后作者栏")
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
