package httpapi

// 推荐流端点（M3 十维算法）测试。
//
// 契约面（换实现不可变）：返回全部资产、参数校验（limit 越界 400）、
// mediaType 过滤、同 seed 可复现；算法序不再是热度序——「顺序」只由
// 确定性算法保证同 seed 一致，不锁具体排列。
// 行为面：每日展示计数落库（先读后写：展示前惩罚、展示后 +1）。

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// recList 拉推荐流。
func recList(t *testing.T, env *testEnv, query string) []gen.AssetSummary {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/recommendations"+query, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("推荐流期望 200，得到 %d", resp.StatusCode)
	}
	var items []gen.AssetSummary
	if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
		t.Fatalf("解析推荐流失败: %v", err)
	}
	return items
}

// fileNames 取响应的文件名集合（去重，断言用）。
func fileNames(items []gen.AssetSummary) map[string]bool {
	names := make(map[string]bool, len(items))
	for _, it := range items {
		if it.FileName != nil {
			names[*it.FileName] = true
		}
	}
	return names
}

// TestRecommendationsReturnsAllAssetsAsSet：契约测试——返回全部资产
// 作为集合（M3 十维算法后顺序不再按热度，锁集合不锁顺序）。
func TestRecommendationsReturnsAllAssetsAsSet(t *testing.T) {
	env := newTestEnv(t)
	items := recList(t, env, "")
	if len(items) != 3 {
		t.Fatalf("推荐流期望 3 条，得到 %d", len(items))
	}
	got := fileNames(items)
	for _, name := range []string{"a.jpg", "b.jpg", "c.mp4"} {
		if !got[name] {
			t.Errorf("推荐流缺少 %s（返回 %v）", name, got)
		}
	}
}

// TestRecommendationsSeedReproducible：同 seed 两次调用顺序一致
// （算法确定性承诺：FNV-1a + 种子化 RNG，DOMAIN_RULES §1.1）。
func TestRecommendationsSeedReproducible(t *testing.T) {
	env := newTestEnv(t)
	first := recList(t, env, "?seed=7")
	second := recList(t, env, "?seed=7")
	if len(first) != 3 || len(second) != 3 {
		t.Fatalf("两次调用都期望 3 条，得到 %d/%d", len(first), len(second))
	}
	for i := range first {
		if first[i].FileName == nil || second[i].FileName == nil ||
			*first[i].FileName != *second[i].FileName {
			t.Fatalf("第 %d 位顺序漂移：%v vs %v", i, first[i].FileName, second[i].FileName)
		}
	}
}

func TestRecommendationsLimitAndFilter(t *testing.T) {
	env := newTestEnv(t)
	if got := len(recList(t, env, "?limit=1")); got != 1 {
		t.Errorf("limit=1 期望 1 条，得到 %d", got)
	}
	videos := recList(t, env, "?mediaType=video")
	if len(videos) != 1 || videos[0].FileName == nil || *videos[0].FileName != "c.mp4" {
		t.Errorf("mediaType=video 期望只含 c.mp4，得到 %v", videos)
	}
}

// TestRecommendationsBadLimit：limit 越界 400（协议 maximum: 200）。
func TestRecommendationsBadLimit(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodGet, "/api/v1/recommendations?limit=500", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("limit=500 期望 400，得到 %d", resp.StatusCode)
	}
}

// TestRecommendationsBadOffset：offset 负数 400（协议 minimum: 0）。
func TestRecommendationsBadOffset(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodGet, "/api/v1/recommendations?offset=-1", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("offset=-1 期望 400，得到 %d", resp.StatusCode)
	}
}

// TestRecommendationsOffsetPaging：offset 翻页无遗漏——page1
// (offset=0&limit=2) ∪ page2 (offset=2&limit=2) == 全量前 4 条集合
// （测试库恰 3 项，前 4 条=全量；越界末页自然截短/深越界为空数组）。
//
// 每次翻页请求前清空 daily_shown：推荐流每次请求按当下打分重排（协议
// offset 描述），首页两项的展示回写（-0.8/次）会不均匀地改变后续请求的
// 排序，令第二页合法地漂移出重复/缺项——那由客户端按 assetId 去重兜底，
// 不属本用例锁定面。这里锁的是「切片本身无遗漏」：清零后各请求打分基线
// 一致、序确定，并集必须严格覆盖全量。
func TestRecommendationsOffsetPaging(t *testing.T) {
	env := newTestEnv(t)
	full := recList(t, env, "?limit=4")
	if len(full) != 3 {
		t.Fatalf("全量期望 3 条，得到 %d", len(full))
	}
	clearDailyShown(t, env)
	page1 := recList(t, env, "?limit=2&offset=0")
	clearDailyShown(t, env)
	page2 := recList(t, env, "?limit=2&offset=2")
	if len(page1) != 2 {
		t.Fatalf("page1 期望 2 条，得到 %d", len(page1))
	}
	if len(page2) != 1 {
		t.Fatalf("page2（末页截短）期望 1 条，得到 %d", len(page2))
	}
	union := fileNames(append(page1, page2...))
	for name := range fileNames(full) {
		if !union[name] {
			t.Errorf("翻页并集缺少全量项 %s（并集 %v）", name, union)
		}
	}
	// 深越界：offset 落在候选规模之后 → 空数组。
	if got := recList(t, env, "?limit=2&offset=99"); len(got) != 0 {
		t.Errorf("offset=99 期望空数组，得到 %d 条", len(got))
	}
}

// clearDailyShown 清空当日展示计数（测试夹具操作，非运行库改动）：
// 供翻页用例在各请求间重置打分基线，见 TestRecommendationsOffsetPaging 注释。
func clearDailyShown(t *testing.T, env *testEnv) {
	t.Helper()
	if _, err := env.conn.ExecContext(context.Background(), "DELETE FROM daily_shown"); err != nil {
		t.Fatalf("清空 daily_shown 失败: %v", err)
	}
}

// TestRecommendationsDailyShown：展示计数先读后写——拉取一次后库内
// 恰好 3 行各 count=1，再拉取各 count=2（刷新不清零当日计数）。
func TestRecommendationsDailyShown(t *testing.T) {
	env := newTestEnv(t)
	if got := len(recList(t, env, "")); got != 3 {
		t.Fatalf("首次拉取期望 3 条，得到 %d", got)
	}
	first := readDailyShown(t, env)
	if len(first) != 3 {
		t.Fatalf("首次拉取后期望 daily_shown 3 行，得到 %d", len(first))
	}
	for id, c := range first {
		if c != 1 {
			t.Errorf("首次拉取后 %s 期望 count=1，得到 %d", id, c)
		}
	}
	recList(t, env, "")
	second := readDailyShown(t, env)
	if len(second) != 3 {
		t.Fatalf("二次拉取后期望 daily_shown 3 行，得到 %d", len(second))
	}
	for id, c := range second {
		if c != 2 {
			t.Errorf("二次拉取后 %s 期望 count=2，得到 %d", id, c)
		}
	}
}

// readDailyShown 直接查 daily_shown 表（asset_id → count）。
func readDailyShown(t *testing.T, env *testEnv) map[string]int {
	t.Helper()
	rows, err := env.conn.QueryContext(context.Background(),
		"SELECT asset_id, count FROM daily_shown")
	if err != nil {
		t.Fatalf("查询 daily_shown 失败: %v", err)
	}
	defer func() { _ = rows.Close() }()
	out := make(map[string]int)
	for rows.Next() {
		var id string
		var count int
		if err := rows.Scan(&id, &count); err != nil {
			t.Fatalf("读取 daily_shown 行失败: %v", err)
		}
		out[id] = count
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("遍历 daily_shown 失败: %v", err)
	}
	return out
}

// TestRecommendationsCardExtras：推荐流是首页默认 tab 的卡片数据源，
// 与 GET /assets 同口径返回卡片增强字段——authorNames（无作者=空数组）
// 与视频 durationMs（图片无值）。作者/时长写入走 store 层（同 browse
// 侧 TestAssetListAuthorNamesAndDuration 的构造方式）。
func TestRecommendationsCardExtras(t *testing.T) {
	env := newTestEnv(t)
	a, c := testFiles[0], testFiles[2]
	ctx := context.Background()
	now := store.FormatTimestamp(env.clock.Now())

	regID := authoring.GenerateAuthorID("画师C")
	if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: regID, DisplayName: "画师C", Type: authoring.AuthorTypeRegular, CreatedAt: now,
	}); err != nil {
		t.Fatalf("UpsertAuthor 失败: %v", err)
	}
	if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: a.id, AuthorID: regID}); err != nil {
		t.Fatalf("AddAssetAuthor 失败: %v", err)
	}
	if _, err := env.q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: c.id, LibraryID: env.libID, RelPath: c.relPath,
		FileName: c.name, MediaType: c.mediaType, SizeBytes: c.size,
		Mtime:      c.mtime,
		DurationMs: sql.NullInt64{Int64: 125000, Valid: true},
		CreatedAt:  now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("写入视频时长失败: %v", err)
	}

	for _, it := range recList(t, env, "") {
		if it.FileName == nil {
			continue
		}
		switch *it.FileName {
		case "a.jpg":
			if an := it.AuthorNames; an == nil || len(*an) != 1 || (*an)[0] != "画师C" {
				t.Errorf("a.jpg authorNames 应为 [画师C]，得到 %v", it.AuthorNames)
			}
			if it.DurationMs != nil {
				t.Errorf("图片 a.jpg 不应带 durationMs，得到 %v", *it.DurationMs)
			}
		case "c.mp4":
			if an := it.AuthorNames; an == nil || len(*an) != 0 {
				t.Errorf("c.mp4 无作者应为空数组，得到 %v", it.AuthorNames)
			}
			if d := it.DurationMs; d == nil || *d != 125000 {
				t.Errorf("c.mp4 durationMs 应 125000，得到 %v", it.DurationMs)
			}
		}
	}
}

// TestRecommendationsCosOnly：COS 推荐模式（协议 cosOnly=true，旧版
// 「COS 推荐模式」语义，DOMAIN_RULES §6）——候选集限定 COS 作者关联
// 资产，缺省常规流继续排除 COS；两条流同口径填充 cosWork（COS 卡片
// 标题数据源；常规资产与无作品子目录的 COS 资产为 null，客户端回退
// fileName）。
func TestRecommendationsCosOnly(t *testing.T) {
	env := newTestEnv(t)
	ctx := context.Background()
	now := store.FormatTimestamp(env.clock.Now())
	a, b := testFiles[0], testFiles[1]

	cosID := authoring.GenerateCosAuthorID("COS酱")
	if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: cosID, DisplayName: "COS酱", Type: authoring.AuthorTypeCos, CreatedAt: now,
	}); err != nil {
		t.Fatalf("UpsertAuthor 失败: %v", err)
	}
	if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: a.id, AuthorID: cosID}); err != nil {
		t.Fatalf("AddAssetAuthor 失败: %v", err)
	}
	// a.jpg 落作品子目录名（COS 库扫描语义：rel_path 第二段，migration 0008）。
	if _, err := env.q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: a.id, LibraryID: env.libID, RelPath: a.relPath,
		FileName: a.name, MediaType: a.mediaType, SizeBytes: a.size,
		Mtime:     a.mtime,
		CosWork:   sql.NullString{String: "8-24 手办", Valid: true},
		CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("写入 cos_work 失败: %v", err)
	}
	// b.jpg：COS 关联但无作品子目录（结构一「作者/文件」平铺形态）。
	if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: b.id, AuthorID: cosID}); err != nil {
		t.Fatalf("AddAssetAuthor 失败: %v", err)
	}

	// 缺省常规流：a/b（COS 关联）被隔离，只剩 c.mp4。
	regular := recList(t, env, "")
	got := fileNames(regular)
	if len(regular) != 1 || got["a.jpg"] || got["b.jpg"] {
		t.Errorf("常规流应排除 COS 关联资产（得到 %v）", got)
	}
	for _, it := range regular {
		if it.CosWork != nil {
			t.Errorf("常规资产 %s 不应带 cosWork，得到 %v", *it.FileName, *it.CosWork)
		}
	}

	// COS 推荐模式：只要 COS 关联资产；有作品子目录的填充 cosWork，
	// 平铺形态的保持 null（回退 fileName）。
	cosItems := recList(t, env, "?cosOnly=true")
	cosGot := fileNames(cosItems)
	if len(cosItems) != 2 || !cosGot["a.jpg"] || !cosGot["b.jpg"] {
		t.Fatalf("cosOnly=true 期望恰含 a.jpg/b.jpg，得到 %v", cosGot)
	}
	byName := map[string]gen.AssetSummary{}
	for _, it := range cosItems {
		if it.FileName != nil {
			byName[*it.FileName] = it
		}
	}
	if w := byName["a.jpg"].CosWork; w == nil || *w != "8-24 手办" {
		t.Errorf("a.jpg cosWork 应为「8-24 手办」，得到 %v", byName["a.jpg"].CosWork)
	}
	if w := byName["b.jpg"].CosWork; w != nil {
		t.Errorf("b.jpg（无作品子目录）cosWork 应为 null，得到 %v", *w)
	}
}
