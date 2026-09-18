package httpapi

// 推荐流响应缓存测试（2026-09-18 性能批）。
//
// 命中路径的排序一致性与计数回写由既有用例跨缓存路径锁定（同 seed 两次调用
// 走 TestRecommendationsSeedReproducible、二次拉取计数走
// TestRecommendationsDailyShown——第二发即缓存命中路径）；本文件补锁定面：
// 修订号作废、TTL 过期、结构性失效端到端（库开关 → 下一发反映）。

import (
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"qimeng-media/server/internal/events"
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
// 经 library.changed 事件的装配期订阅统一失效（server.go New）。
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

// TestLibraryChangedEventInvalidatesRecommendCache：白盒锁定失效主通道——
// 任何 library.changed 事件发布都必须推进推荐缓存修订号（watch 增量事件等
// 不经过 handler 的发布点依赖此通道）。
func TestLibraryChangedEventInvalidatesRecommendCache(t *testing.T) {
	env := newTestEnv(t)
	_ = recList(t, env, "?seed=5") // 落缓存
	revBefore := env.s.recommendCache.revision()
	if err := env.s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		t.Fatalf("发布事件失败: %v", err)
	}
	// 订阅消费在装配期 goroutine 里异步执行：轮询等待修订号前进
	deadline := time.Now().Add(2 * time.Second)
	for env.s.recommendCache.revision() == revBefore && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if env.s.recommendCache.revision() == revBefore {
		t.Fatal("library.changed 事件未触发推荐缓存失效")
	}
}

// TestRecommendPrewarmServesWithoutCounting：预热只算不服务——预热后再来
// 首条请求，展示计数必须恰好 +1/项（预热若违规计数会得到 2）。
func TestRecommendPrewarmServesWithoutCounting(t *testing.T) {
	env := newTestEnv(t)
	env.s.prewarmRecommend() // 同步跑预热（StartRecommendPrewarm 的 goroutine 体）
	items := recList(t, env, "?seed=1&limit=200")
	if len(items) != 3 {
		t.Fatalf("预热后首条请求期望 3 条，得到 %d", len(items))
	}
	for id, c := range readDailyShown(t, env) {
		if c != 1 {
			t.Errorf("预热不得写展示计数、请求恰 +1：%s=%d", id, c)
		}
	}
}

// TestRecommendCacheSingleflight：并发同键只跑一次计算，等待方共享同一结果
// （冷启动首条请求与开机预热的合并语义）。
func TestRecommendCacheSingleflight(t *testing.T) {
	c := newRecommendCache()
	key := recommendCacheKey{rev: 0, day: "2026-09-18", seed: 1, offset: 0, limit: 10}
	release := make(chan struct{})
	var calls int32
	const waiters = 3
	var wg sync.WaitGroup
	errs := make([]error, waiters)
	for i := 0; i < waiters; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, _, errs[i] = c.do(key, func() ([]gen.AssetSummary, []string, error) {
				atomic.AddInt32(&calls, 1)
				<-release
				return []gen.AssetSummary{}, []string{"a", "b"}, nil
			})
		}(i)
	}
	time.Sleep(50 * time.Millisecond) // 放行前让全部等待方进入 do
	close(release)
	wg.Wait()
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("并发同键应单飞一次计算，实际 %d 次", calls)
	}
	for i, err := range errs {
		if err != nil {
			t.Fatalf("等待方 %d 应共享成功结果: %v", i, err)
		}
	}
}
