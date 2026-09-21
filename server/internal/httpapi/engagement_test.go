package httpapi

// M3 会话去重测试（DOMAIN_RULES §5「同一会话内只计一次」）：
//   - open/play 同会话当日只计 1（重复上报 202 幂等，不插事件不累加）
//   - 不同会话各计 1
//   - dwell 不去重且秒数累加（时长是累加量，去重会丢真实停留时间）
//   - 跨日后同会话再计 1（去重窗口 = started_at 的本地日历日）
// 物化表 asset_daily_stats 与事件流同事务同步累加，一并断言。

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// postDwellEvent 上报一条带秒数的 dwell 事件。
func postDwellEvent(t *testing.T, env *testEnv, assetID, session string, at time.Time, seconds float64) {
	t.Helper()
	body, err := json.Marshal(gen.ViewEventReport{
		AssetId:   uuid.MustParse(assetID),
		Kind:      gen.Dwell,
		SessionId: session,
		StartedAt: at,
		Seconds:   ptr(seconds),
	})
	if err != nil {
		t.Fatalf("构造 dwell 请求失败: %v", err)
	}
	resp := env.do(t, http.MethodPost, "/api/v1/events/view", string(body))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("dwell 上报期望 202，得到 %d", resp.StatusCode)
	}
}

// countViewEvents 直接数事件流中某资产某 kind 的行数。
func countViewEvents(t *testing.T, env *testEnv, assetID, kind string) int {
	t.Helper()
	rows, err := env.conn.QueryContext(context.Background(),
		"SELECT COUNT(*) FROM view_events WHERE asset_id = ? AND kind = ?", assetID, kind)
	if err != nil {
		t.Fatalf("查询事件流失败: %v", err)
	}
	defer func() { _ = rows.Close() }()
	if !rows.Next() {
		t.Fatal("COUNT 查询无结果行")
	}
	var n int
	if err := rows.Scan(&n); err != nil {
		t.Fatalf("读取计数失败: %v", err)
	}
	return n
}

// readDailyStats 读物化表某资产某日的聚合值（未找到 found=false）。
func readDailyStats(t *testing.T, env *testEnv, assetID, day string) (view, play, secs int64, found bool) {
	t.Helper()
	rows, err := env.conn.QueryContext(context.Background(),
		"SELECT view_count, play_count, browse_seconds FROM asset_daily_stats WHERE asset_id = ? AND day = ?",
		assetID, day)
	if err != nil {
		t.Fatalf("查询物化表失败: %v", err)
	}
	defer func() { _ = rows.Close() }()
	if !rows.Next() {
		return 0, 0, 0, false
	}
	if err := rows.Scan(&view, &play, &secs); err != nil {
		t.Fatalf("读取物化行失败: %v", err)
	}
	return view, play, secs, true
}

// TestEngagementSessionDedup：同会话去重 + 异会话计数 + 物化表同步。
func TestEngagementSessionDedup(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	today := store.FormatDay(env.clock.Now())

	// 同会话重复 open ×2：事件 1 行，物化 view=1
	postViewEvent(t, env, a.id, gen.Open, "sess-1", env.clock.Now())
	postViewEvent(t, env, a.id, gen.Open, "sess-1", env.clock.Now())
	if n := countViewEvents(t, env, a.id, "open"); n != 1 {
		t.Fatalf("同会话重复 open 期望事件 1 行，得到 %d", n)
	}
	if view, _, _, ok := readDailyStats(t, env, a.id, today); !ok || view != 1 {
		t.Fatalf("同会话重复 open 期望物化 view=1，得到 view=%d found=%v", view, ok)
	}

	// 不同会话 open：事件 2 行，物化 view=2
	postViewEvent(t, env, a.id, gen.Open, "sess-2", env.clock.Now())
	if n := countViewEvents(t, env, a.id, "open"); n != 2 {
		t.Fatalf("不同会话各计 1 期望事件 2 行，得到 %d", n)
	}
	if view, _, _, _ := readDailyStats(t, env, a.id, today); view != 2 {
		t.Fatalf("不同会话后期望物化 view=2，得到 %d", view)
	}

	// play 同会话去重：与 open 独立计数（kind 是去重维度之一）
	postViewEvent(t, env, a.id, gen.Play, "sess-1", env.clock.Now())
	postViewEvent(t, env, a.id, gen.Play, "sess-1", env.clock.Now())
	if n := countViewEvents(t, env, a.id, "play"); n != 1 {
		t.Fatalf("同会话重复 play 期望事件 1 行，得到 %d", n)
	}
	if _, play, _, _ := readDailyStats(t, env, a.id, today); play != 1 {
		t.Fatalf("期望物化 play=1，得到 %d", play)
	}
}

// TestEngagementDwellNotDeduped：dwell 不去重且秒数累加。
func TestEngagementDwellNotDeduped(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	today := store.FormatDay(env.clock.Now())

	postDwellEvent(t, env, a.id, "sess-1", env.clock.Now(), 30)
	postDwellEvent(t, env, a.id, "sess-1", env.clock.Now(), 45)
	if n := countViewEvents(t, env, a.id, "dwell"); n != 2 {
		t.Fatalf("dwell 不去重：期望事件 2 行，得到 %d", n)
	}
	if _, _, secs, ok := readDailyStats(t, env, a.id, today); !ok || secs != 75 {
		t.Fatalf("dwell 秒数期望累加 30+45=75，得到 secs=%d found=%v", secs, ok)
	}
	// dwell 不影响 view/play 计数
	if view, play, _, _ := readDailyStats(t, env, a.id, today); view != 0 || play != 0 {
		t.Fatalf("dwell 不应计入 view/play，得到 view=%d play=%d", view, play)
	}
}

// TestEngagementDedupResetsNextDay：跨日后同会话再计 1（当日窗口重置）。
func TestEngagementDedupResetsNextDay(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	firstDay := store.FormatDay(env.clock.Now())

	postViewEvent(t, env, a.id, gen.Open, "sess-1", env.clock.Now())
	env.clock.advance(24 * time.Hour)
	postViewEvent(t, env, a.id, gen.Open, "sess-1", env.clock.Now()) // 同会话次日再开

	if n := countViewEvents(t, env, a.id, "open"); n != 2 {
		t.Fatalf("跨日后同会话再计 1：期望事件 2 行，得到 %d", n)
	}
	// 两天各自一行物化聚合，互不吞并
	if view, _, _, ok := readDailyStats(t, env, a.id, firstDay); !ok || view != 1 {
		t.Fatalf("首日物化行期望 view=1，得到 view=%d found=%v", view, ok)
	}
	nextDay := store.FormatDay(env.clock.Now())
	if view, _, _, ok := readDailyStats(t, env, a.id, nextDay); !ok || view != 1 {
		t.Fatalf("次日物化行期望 view=1，得到 view=%d found=%v", view, ok)
	}
}

// TestEngagementSessionDedupBackstop（审计 R10，2026-09-20）：migration
// 0014 部分唯一索引是「先查后插」TOCTOU 窗口的数据库层兜底。分两层验证：
//  1. db 层确定性——同键 open 二次插入返回 0 行（索引拦重），dwell 与
//     NULL day（导入回放形态）不受索引约束；
//  2. handler 层竞态——并发同键 open 全部 202，事件流恰 1 行、物化恰 1。
func TestEngagementSessionDedupBackstop(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	day := store.FormatDay(env.clock.Now())

	// db 层：输家插入 0 行；dwell / NULL day 不被索引拦截
	tx, err := env.conn.BeginTx(context.Background(), nil)
	if err != nil {
		t.Fatalf("开事务失败: %v", err)
	}
	defer func() { _ = tx.Rollback() }()
	qtx := env.q.WithTx(tx)
	base := db.InsertViewEventIdempotentParams{
		AssetID: a.id, Kind: "open", SessionID: "sess-bk",
		StartedAt: store.FormatTimestamp(env.clock.Now()),
		Day:       sql.NullString{String: day, Valid: true},
	}
	if n, err := qtx.InsertViewEventIdempotent(context.Background(), base); err != nil || n != 1 {
		t.Fatalf("首插应 1 行, got %d, err %v", n, err)
	}
	if n, err := qtx.InsertViewEventIdempotent(context.Background(), base); err != nil || n != 0 {
		t.Fatalf("同键二插应被 0014 索引拦成 0 行, got %d, err %v", n, err)
	}
	dwell := base
	dwell.Kind = "dwell"
	if n, err := qtx.InsertViewEventIdempotent(context.Background(), dwell); err != nil || n != 1 {
		t.Fatalf("dwell 不受去重索引约束, got %d, err %v", n, err)
	}
	legacy := base
	legacy.Day = sql.NullString{} // 导入回放形态：day NULL
	if n, err := qtx.InsertViewEventIdempotent(context.Background(), legacy); err != nil || n != 1 {
		t.Fatalf("NULL day（导入回放）不应被索引拦截, got %d, err %v", n, err)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatalf("回滚失败: %v", err)
	}

	// handler 层：并发同键 open，全部 202，恰 1 行。goroutine 内不走 env.do
	// ——其构造/传输失败分支直接 t.Fatalf，而 testing 包要求 FailNow 只能在
	// 测试主 goroutine 调用（goroutine 内 Goexit 会让下方收包循环永等挂起）；
	// 这里改走 errs 通道（2026-09-21 维护批，测试基建健壮性）。
	const n = 8
	errs := make(chan error, n)
	for i := 0; i < n; i++ {
		go func() {
			body, err := json.Marshal(gen.ViewEventReport{
				AssetId:   uuid.MustParse(a.id),
				Kind:      gen.Open,
				SessionId: "sess-race",
				StartedAt: env.clock.Now(),
			})
			if err != nil {
				errs <- err
				return
			}
			req, err := http.NewRequest(http.MethodPost, env.ts.URL+"/api/v1/events/view", strings.NewReader(string(body)))
			if err != nil {
				errs <- err
				return
			}
			req.Header.Set("Authorization", "Bearer "+env.token)
			req.Header.Set("Content-Type", "application/json")
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				errs <- err
				return
			}
			defer func() { _ = resp.Body.Close() }()
			if resp.StatusCode != http.StatusAccepted {
				errs <- fmt.Errorf("期望 202, 得到 %d", resp.StatusCode)
				return
			}
			errs <- nil
		}()
	}
	for i := 0; i < n; i++ {
		if err := <-errs; err != nil {
			t.Fatalf("并发上报失败: %v", err)
		}
	}
	if got := countViewEvents(t, env, a.id, "open"); got != 1 {
		t.Fatalf("并发后 open 事件应恰 1 行（0014 兜底）, got %d", got)
	}
	view, _, _, found := readDailyStats(t, env, a.id, day)
	if !found || view != 1 {
		t.Fatalf("物化 view_count 应为 1, got %d (found=%v)", view, found)
	}
}
