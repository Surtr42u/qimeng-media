package httpapi

// 推荐流响应缓存测试（2026-09-18 性能批）。
//
// 命中路径的排序一致性与计数回写由既有用例跨缓存路径锁定（同 seed 两次调用
// 走 TestRecommendationsSeedReproducible、二次拉取计数走
// TestRecommendationsDailyShown——第二发即缓存命中路径）；本文件补锁定面：
// 修订号作废、TTL 过期、结构性失效端到端（库开关 → 下一发反映）。

import (
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
)

func TestRecommendCacheInvalidateDropsEntries(t *testing.T) {
	c := newRecommendCache()
	key := recommendCacheKey{rev: c.revision(), day: "2026-09-18", seed: 0, offset: 0, limit: 5}
	c.put(key, []gen.AssetSummary{}, []string{"a"})
	if _, ok := c.get(key); !ok {
		t.Fatal("put 后应命中")
	}
	c.invalidate()
	if _, ok := c.get(key); ok {
		t.Fatal("invalidate 后旧修订号键不应命中")
	}
	// 新修订号下的同参键是新键，可正常命中（失效 ≠ 永久禁用）
	key2 := key
	key2.rev = c.revision()
	c.put(key2, []gen.AssetSummary{}, []string{"a"})
	if _, ok := c.get(key2); !ok {
		t.Fatal("新修订号键应命中")
	}
}

func TestRecommendCacheTTLExpiry(t *testing.T) {
	c := newRecommendCache()
	key := recommendCacheKey{rev: 0, day: "2026-09-18", seed: 1, offset: 0, limit: 10}
	c.put(key, []gen.AssetSummary{}, []string{})
	if _, ok := c.get(key); !ok {
		t.Fatal("刚存入应命中")
	}
	// 白盒拨老条目时间：TTL 判定基于 storedAt，不依赖可注入时钟
	c.mu.Lock()
	e := c.entries[key]
	e.storedAt = time.Now().Add(-recommendCacheTTL - time.Second)
	c.entries[key] = e
	c.mu.Unlock()
	if _, ok := c.get(key); ok {
		t.Fatal("过期条目不应命中")
	}
}

// TestRecommendationsCacheInvalidatedOnLibraryDisable：端到端锁定「结构性
// 变更必须主动失效缓存」——禁用库后候选集变化，若缓存未失效，同参数下一发
// 仍会返回禁用前的 3 条。库开关是候选查询的 WHERE 条件（recommend.sql），
// 是失效钩子挂点之一（libraries.go enabled 端点）。
func TestRecommendationsCacheInvalidatedOnLibraryDisable(t *testing.T) {
	env := newTestEnv(t)
	if got := recList(t, env, "?seed=3"); len(got) != 3 {
		t.Fatalf("首次拉取期望 3 条，得到 %d", len(got))
	}
	resp := env.do(t, http.MethodPut, "/api/v1/libraries/"+env.libID+"/enabled", `{"enabled":false}`)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("禁用库期望 204，得到 %d", resp.StatusCode)
	}
	if got := recList(t, env, "?seed=3"); len(got) != 0 {
		t.Fatalf("禁用库后推荐应为空（缓存未失效？），得到 %d 条", len(got))
	}
}
