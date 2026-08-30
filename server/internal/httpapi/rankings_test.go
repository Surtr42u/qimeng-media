package httpapi

// 排行榜端点（M3）测试：无周期内浏览 → 200 空数组；周期过滤只留
// 当日浏览过的资产；limit 生效。热度口径（view+play+like）已在
// recommend 包单测锁定，这里只测接线（HTTP 层行为）。

import (
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// rankList 拉排行榜。
func rankList(t *testing.T, env *testEnv, query string) []gen.AssetSummary {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/rankings"+query, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("排行榜期望 200，得到 %d", resp.StatusCode)
	}
	var items []gen.AssetSummary
	if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
		t.Fatalf("解析排行榜失败: %v", err)
	}
	return items
}

// reportView 上报一次 open 浏览事件（startedAt 固定为测试钟当天上午）。
func reportView(t *testing.T, env *testEnv, assetID, session string) {
	t.Helper()
	resp := env.do(t, http.MethodPost, "/api/v1/events/view",
		`{"assetId":"`+assetID+`","kind":"open","startedAt":"2026-08-22T10:00:00Z","sessionId":"`+session+`"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("view 上报期望 202，得到 %d", resp.StatusCode)
	}
}

// TestRankingsNoViewsInPeriod_returnsEmpty：day 周期下无任何浏览
// 记录 → 200 空数组（有库无数据也保持 200，不是 404）。
func TestRankingsNoViewsInPeriod_returnsEmpty(t *testing.T) {
	env := newTestEnv(t)
	items := rankList(t, env, "?period=day")
	if len(items) != 0 {
		t.Fatalf("无浏览时 day 榜期望空数组，得到 %v", items)
	}
}

// TestRankingsDayPeriod_returnsViewedAsset：打浏览事件后当天 rank
// 返回该资产（周期过滤口径 = 最近浏览时间在窗口内）。
func TestRankingsDayPeriod_returnsViewedAsset(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "b.jpg")
	if !ok {
		t.Fatal("测试前置失败：b.jpg 不在列表")
	}
	reportView(t, env, id, "s-day")
	items := rankList(t, env, "?period=day")
	if len(items) != 1 || items[0].FileName == nil || *items[0].FileName != "b.jpg" {
		t.Fatalf("day 榜期望只含 b.jpg，得到 %v", items)
	}
}

// TestRankingsLimit：limit 生效（热度高的 b.jpg 排在前面，limit=1 只出它）。
func TestRankingsLimit(t *testing.T) {
	env := newTestEnv(t)
	bID, okB := env.assetIDByName(t, "b.jpg")
	cID, okC := env.assetIDByName(t, "c.mp4")
	if !okB || !okC {
		t.Fatal("测试前置失败：测试资产缺失")
	}
	reportView(t, env, bID, "s-b1")
	reportView(t, env, bID, "s-b2")
	reportView(t, env, cID, "s-c1")
	items := rankList(t, env, "?period=all&limit=1")
	if len(items) != 1 || items[0].FileName == nil || *items[0].FileName != "b.jpg" {
		t.Fatalf("limit=1 期望只出热度最高的 b.jpg，得到 %v", items)
	}
}
