package httpapi

// ADR-0029 跨端数据新鲜度收敛第一半：favorite/like 写路径成功后发布
// favorite.changed / like.changed（载荷=变更资产 id），失败路径零发布；
// 且两类事件不得推动库内容修订号、不得混发 library.changed（ADR-0026
// 语义边界：revision 只保证资产集合面，收藏/点赞不改变集合——这层契约
// 在 handler 与 server.go 订阅两侧各赖注释自觉，这里用行为测试钉死）。

import (
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi/gen"
)

// subscribeEngagementEvents 订阅两类收藏/点赞事件（外加 library.changed
// 作"混发"反证），返回非阻塞收取已到事件的函数。
//
// 非阻塞即可断言的原因：事件发布点在 handler 写响应之前（同步路径），
// 测试拿到 HTTP 响应时事件必然已进订阅缓冲——HTTP 响应到达是发布完成
// 之后的 happens-after 事实，不存在"再等等"的时序。
func subscribeEngagementEvents(t *testing.T, env *testEnv) func() []events.Event {
	t.Helper()
	sub, err := env.s.bus.Subscribe(events.TopicFavoriteChanged, events.TopicLikeChanged, events.TopicLibraryChanged)
	if err != nil {
		t.Fatalf("订阅事件失败: %v", err)
	}
	t.Cleanup(sub.Close)
	return func() []events.Event {
		var got []events.Event
		for {
			select {
			case e, ok := <-sub.C:
				if !ok {
					return got
				}
				got = append(got, e)
			default:
				return got
			}
		}
	}
}

// assertSingleEvent 断言收取结果恰为一条指定 topic、载荷指向指定资产的事件。
func assertSingleEvent(t *testing.T, got []events.Event, topic, assetID string) {
	t.Helper()
	if len(got) != 1 {
		t.Fatalf("期望恰 1 条 %s 事件，得到 %d 条", topic, len(got))
	}
	if got[0].Topic != topic {
		t.Fatalf("事件 topic = %q, want %q", got[0].Topic, topic)
	}
	p, ok := got[0].Payload.(events.EngagementChangedEvent)
	if !ok {
		t.Fatalf("载荷类型 %T, want events.EngagementChangedEvent", got[0].Payload)
	}
	if p.AssetID != assetID {
		t.Fatalf("载荷 assetId = %q, want %q", p.AssetID, assetID)
	}
}

// putFavoriteBody / putLike 走 env.do 发收藏/点赞请求并校验成功状态码。
func putFavoriteBody(t *testing.T, env *testEnv, assetID, body string) {
	t.Helper()
	resp := env.do(t, http.MethodPut, "/api/v1/assets/"+assetID+"/favorite", body)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("收藏请求期望 204，得到 %d（asset %s body %s）", resp.StatusCode, assetID, body)
	}
}

func putLike(t *testing.T, env *testEnv, assetID string) {
	t.Helper()
	resp := env.do(t, http.MethodPut, "/api/v1/assets/"+assetID+"/like", "")
	defer func() { _ = resp.Body.Close() }()
	var state gen.LikeState
	if err := json.NewDecoder(resp.Body).Decode(&state); err != nil {
		t.Fatalf("解析 LikeState 失败: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("点赞请求期望 200，得到 %d", resp.StatusCode)
	}
}

// getRevision 读当前库内容修订号（锁定"favorite/like 不 bump 修订号"用）。
func getRevision(t *testing.T, env *testEnv) int64 {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/library/revision", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("读修订号期望 200，得到 %d", resp.StatusCode)
	}
	var rev gen.LibraryRevision
	if err := json.NewDecoder(resp.Body).Decode(&rev); err != nil {
		t.Fatalf("解析 LibraryRevision 失败: %v", err)
	}
	return rev.Revision
}

// TestEngagementChangeEventsPublished：收藏/取消、点赞/取消各发布一条
// 对应事件（topic + 载荷 assetId）；失败路径（资产不存在 404）零发布；
// 全程不发 library.changed、修订号纹丝不动。
func TestEngagementChangeEventsPublished(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	collect := subscribeEngagementEvents(t, env)
	revBefore := getRevision(t, env)

	// 收藏设置 → favorite.changed；取消 → 再一条 favorite.changed
	putFavoriteBody(t, env, a.id, `{"favorite":true}`)
	assertSingleEvent(t, collect(), events.TopicFavoriteChanged, a.id)
	putFavoriteBody(t, env, a.id, `{"favorite":false}`)
	assertSingleEvent(t, collect(), events.TopicFavoriteChanged, a.id)

	// 重复收藏是幂等 no-op（ON CONFLICT DO NOTHING），照发一条（见发布点注释：
	// 消费方多刷幂等无害，发布点与写成功点一一对应）——两次请求各恰一条
	putFavoriteBody(t, env, a.id, `{"favorite":true}`)
	putFavoriteBody(t, env, a.id, `{"favorite":true}`)
	noops := collect()
	if len(noops) != 2 {
		t.Fatalf("重复收藏两次应各发一条 favorite.changed，得到 %d 条", len(noops))
	}
	for _, e := range noops {
		p, ok := e.Payload.(events.EngagementChangedEvent)
		if e.Topic != events.TopicFavoriteChanged || !ok || p.AssetID != a.id {
			t.Fatalf("重复收藏事件异常: topic=%q payload=%+v", e.Topic, e.Payload)
		}
	}

	// 点赞 toggle 开 → like.changed；再 toggle 关 → like.changed
	putLike(t, env, a.id)
	assertSingleEvent(t, collect(), events.TopicLikeChanged, a.id)
	putLike(t, env, a.id)
	assertSingleEvent(t, collect(), events.TopicLikeChanged, a.id)

	// 失败路径：资产不存在（404）在写路径之前拦截，零发布
	ghost := "00000000-0000-0000-0000-0000000000ab" // 从未入库
	resp := env.do(t, http.MethodPut, "/api/v1/assets/"+ghost+"/favorite", `{"favorite":true}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("不存在资产收藏期望 404，得到 %d", resp.StatusCode)
	}
	resp = env.do(t, http.MethodPut, "/api/v1/assets/"+ghost+"/like", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("不存在资产点赞期望 404，得到 %d", resp.StatusCode)
	}
	if got := collect(); len(got) != 0 {
		t.Fatalf("失败路径不应发布事件，得到 %d 条", len(got))
	}

	// 全程只发过 favorite/like 两类事件：library.changed 一条都不该有
	// （server.go 的修订号自增订阅只挂在该事件上，零混发 ⇒ 零误 bump）。
	// 修订号端点值本身再验一遍，双保险锁死 ADR-0026 语义边界。
	for _, e := range collect() {
		if e.Topic == events.TopicLibraryChanged {
			t.Fatalf("favorite/like 路径混发了 library.changed: %+v", e)
		}
	}
	if revAfter := getRevision(t, env); revAfter != revBefore {
		t.Fatalf("收藏/点赞不得推动修订号（ADR-0026）：before=%d after=%d", revBefore, revAfter)
	}
}
