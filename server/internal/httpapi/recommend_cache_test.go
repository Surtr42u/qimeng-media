package httpapi

import (
	"context"
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/events"
)

func TestImageMetadataRefreshDoesNotInvalidateRecommendationOrCollectionRevision(t *testing.T) {
	env := newTestEnv(t)
	_ = recList(t, env, "?seed=5")
	cacheBefore, revisionBefore := env.s.recommendCache.revision(), env.revisionNow(t)
	if err := env.s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged, LibraryChange: &events.LibraryChangeScope{MetadataOnly: true}}); err != nil {
		t.Fatal(err)
	}
	// 同订阅FIFO的普通事件作处理屏障；正常变更应且只应推进一次。
	if err := env.s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		t.Fatal(err)
	}
	env.waitRevision(t, revisionBefore+1)
	if got := env.s.recommendCache.revision(); got != cacheBefore+1 {
		t.Fatalf("metadata caused redundant cold recomputation: %d -> %d", cacheBefore, got)
	}
}

// 冷算需要事件表，取页摘要不需要；预热后临时改名使错误键无法重新冷算。
func TestRecommendPrewarmCoversWebAndApp(t *testing.T) {
	env := newTestEnv(t)
	env.s.prewarmRecommend()
	for id, count := range readDailyShown(t, env) {
		if count != 0 {
			t.Fatalf("预热错误计数: %s=%d", id, count)
		}
	}
	if _, err := env.conn.ExecContext(context.Background(), "ALTER TABLE view_events RENAME TO unavailable_view_events"); err != nil {
		t.Fatal(err)
	}
	for _, query := range []string{"?seed=0&limit=60", "?seed=1&limit=200", "?seed=0&limit=1&offset=1"} {
		if items := recList(t, env, query); len(items) == 0 {
			t.Fatalf("预热没有覆盖 %s", query)
		}
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
