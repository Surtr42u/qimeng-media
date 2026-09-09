package httpapi

// 任务L L5 客户端幂等测试（拍板 #7「本地优先 + 发送成功再删 + clientEventId
// 服务端幂等」；协议 migration 0010 + InsertViewEventIdempotent）：
//   - 同 clientEventId 重发（模拟断网补传重试）→ 202 成功返回但不双计：
//     open/play 不重复计数、dwell 秒数不重复累加，事件流与物化表同步不变
//   - 两端并集：不同 sessionId 各自携带不同 id → 各计一条（Web∪Android 合并口径）
//   - 幂等键优先于会话去重：跨会话误用同 id 也只计一条（唯一索引兜底）
//   - 旧格式请求（未携带 clientEventId，解码为零值 uuid.Nil）行为 = 放行：
//     照常入库计数（engagement.go 函数头注释的口径锁定）
//
// 物化表 asset_daily_stats 与事件流同事务累加，一并断言（不双计必须两边都成立）。

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
)

// postViewEventWithId 上报一条携带客户端幂等键的浏览事件。
func postViewEventWithId(t *testing.T, env *testEnv, assetID string, kind gen.ViewEventReportKind, session, clientEventID string, at time.Time, seconds *float64) {
	t.Helper()
	report := gen.ViewEventReport{
		AssetId:       uuid.MustParse(assetID),
		Kind:          kind,
		SessionId:     session,
		StartedAt:     at,
		ClientEventId: uuid.MustParse(clientEventID),
	}
	if seconds != nil {
		report.Seconds = seconds
	}
	body, err := json.Marshal(report)
	if err != nil {
		t.Fatalf("构造幂等事件请求失败: %v", err)
	}
	resp := env.do(t, http.MethodPost, "/api/v1/events/view", string(body))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("事件上报期望 202，得到 %d", resp.StatusCode)
	}
}

// TestEngagementClientEventIdempotency：同 id 重发不双计 + 两端并集 + 键优先于会话。
func TestEngagementClientEventIdempotency(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	today := store.FormatDay(env.clock.Now())
	now := env.clock.Now()

	// ① open 携带 id X 重发两次（断网补传重演）：事件 1 行、物化 view=1
	postViewEventWithId(t, env, a.id, gen.Open, "sess-web", "00000000-0000-0000-0000-0000000000a1", now, nil)
	postViewEventWithId(t, env, a.id, gen.Open, "sess-web", "00000000-0000-0000-0000-0000000000a1", now, nil)
	if n := countViewEvents(t, env, a.id, "open"); n != 1 {
		t.Fatalf("同 clientEventId 重发 open 期望事件 1 行，得到 %d", n)
	}
	if view, _, _, ok := readDailyStats(t, env, a.id, today); !ok || view != 1 {
		t.Fatalf("同 clientEventId 重发 open 期望物化 view=1，得到 view=%d found=%v", view, ok)
	}

	// ② 两端并集：不同会话（Web/Android）各带不同 id 各计一条 → 事件 2 行、view=2
	postViewEventWithId(t, env, a.id, gen.Open, "sess-android", "00000000-0000-0000-0000-0000000000b2", now, nil)
	if n := countViewEvents(t, env, a.id, "open"); n != 2 {
		t.Fatalf("两端并集期望事件 2 行，得到 %d", n)
	}
	if view, _, _, _ := readDailyStats(t, env, a.id, today); view != 2 {
		t.Fatalf("两端并集期望物化 view=2，得到 %d", view)
	}

	// ③ 幂等键优先于会话：另一会话误用同 id X → 仍只计原条（不新增）
	postViewEventWithId(t, env, a.id, gen.Open, "sess-other", "00000000-0000-0000-0000-0000000000a1", now, nil)
	if n := countViewEvents(t, env, a.id, "open"); n != 2 {
		t.Fatalf("跨会话同 id 期望仍 2 行，得到 %d", n)
	}

	// ④ dwell 携带同 id 重传：秒数不重复累加（30 只记一次），新 id 照常累加
	//（同一会话同日已计一条——物化 30+45=75）
	postViewEventWithId(t, env, a.id, gen.Dwell, "sess-android", "00000000-0000-0000-0000-0000000000c3", now, ptr(float64(30)))
	postViewEventWithId(t, env, a.id, gen.Dwell, "sess-android", "00000000-0000-0000-0000-0000000000c3", now, ptr(float64(30)))
	if n := countViewEvents(t, env, a.id, "dwell"); n != 1 {
		t.Fatalf("dwell 同 id 重传期望事件 1 行，得到 %d", n)
	}
	postViewEventWithId(t, env, a.id, gen.Dwell, "sess-android", "00000000-0000-0000-0000-0000000000d4", now, ptr(float64(45)))
	if _, _, secs, ok := readDailyStats(t, env, a.id, today); !ok || secs != 75 {
		t.Fatalf("dwell 幂等后期望秒数 30+45=75，得到 secs=%d found=%v", secs, ok)
	}
}

// TestEngagementLegacyFormatAllowed：旧格式（不带 clientEventId）放行——
// 照常入库计数；同会话重复仍由既有会话去重吸收（DOMAIN_RULES §5 不变）。
func TestEngagementLegacyFormatAllowed(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	today := store.FormatDay(env.clock.Now())
	now := env.clock.Now()

	postViewEvent(t, env, a.id, gen.Open, "legacy-sess", now) // 零值 id = NULL 放行
	if n := countViewEvents(t, env, a.id, "open"); n != 1 {
		t.Fatalf("旧格式 open 放行期望事件 1 行，得到 %d", n)
	}
	if view, _, _, ok := readDailyStats(t, env, a.id, today); !ok || view != 1 {
		t.Fatalf("旧格式 open 放行期望物化 view=1，得到 view=%d found=%v", view, ok)
	}
	// 旧格式同会话重复：会话去重照常生效（不是唯一索引、也不是拒绝）
	postViewEvent(t, env, a.id, gen.Open, "legacy-sess", now)
	if n := countViewEvents(t, env, a.id, "open"); n != 1 {
		t.Fatalf("旧格式同会话重复期望仍 1 行，得到 %d", n)
	}
	// NULL id 行与带 id 行共存：唯一索引多 NULL 不冲突
	//（换会话绕开会话去重——同会话同日的 open/play 在幂等插入之前就被 §5 挡下）
	postViewEventWithId(t, env, a.id, gen.Open, "mixed-sess", "00000000-0000-0000-0000-0000000000e5", now, nil)
	if n := countViewEvents(t, env, a.id, "open"); n != 2 {
		t.Fatalf("NULL id 与带 id 共存期望 2 行，得到 %d", n)
	}
}
