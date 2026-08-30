package httpapi

// 推荐流端点（M3 十维算法）测试。
//
// 契约面（换实现不可变）：返回全部资产、参数校验（limit 越界 400）、
// mediaType 过滤、同 seed 可复现；算法序不再是热度序——「顺序」只由
// 确定性算法保证同 seed 一致，不锁具体排列。
// 行为面：每日展示计数落库（先读后写：展示前惩罚、展示后 +1）。

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
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
