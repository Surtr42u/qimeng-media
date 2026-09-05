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

// TestRankingsQuarterPeriod_fillsCounts：period=quarter（近 90 天）返回
// 200 且 viewCount/playCount 填充真实值（openapi AssetSummary 新增字段的
// 排行榜填充端点，数据源 ListAssetsRecommendInput 聚合）。
func TestRankingsQuarterPeriod_fillsCounts(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("测试前置失败：a.jpg 不在列表")
	}
	// open ×1 + play ×1（kind 不同，会话去重互不影响）
	reportView(t, env, id, "q-open")
	postPlayEvent(t, env, id, "q-play")

	items := rankList(t, env, "?period=quarter")
	if len(items) != 1 {
		t.Fatalf("quarter 榜应含 1 条，得到 %d", len(items))
	}
	if items[0].ViewCount == nil || *items[0].ViewCount != 1 {
		t.Fatalf("viewCount 应 1：%v", items[0].ViewCount)
	}
	if items[0].PlayCount == nil || *items[0].PlayCount != 1 {
		t.Fatalf("playCount 应 1：%v", items[0].PlayCount)
	}
}

// postPlayEvent 上报一次 play 事件（同一会话当日同 kind 只计一次，
// 这里每次用全新鲜 session 保证计入）。
func postPlayEvent(t *testing.T, env *testEnv, assetID, session string) {
	t.Helper()
	resp := env.do(t, http.MethodPost, "/api/v1/events/view",
		`{"assetId":"`+assetID+`","kind":"play","startedAt":"2026-08-22T10:00:00Z","sessionId":"`+session+`"}`)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("play 上报期望 202，得到 %d", resp.StatusCode)
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

// TestRankingsOffsetPaging：offset 翻页切片（协议 offset，默认 0）——
// 热度序 [b(2), c(1), a(0)]，page1 (offset=0&limit=1) ∪ page2
// (offset=1&limit=1) == 前两条集合；深越界为空数组。排行榜无展示计数
// 回写，各请求热度基线一致，无需清库即可锁并集无遗漏。
func TestRankingsOffsetPaging(t *testing.T) {
	env := newTestEnv(t)
	bID, okB := env.assetIDByName(t, "b.jpg")
	cID, okC := env.assetIDByName(t, "c.mp4")
	if !okB || !okC {
		t.Fatal("测试前置失败：测试资产缺失")
	}
	reportView(t, env, bID, "s-off1")
	reportView(t, env, bID, "s-off2")
	reportView(t, env, cID, "s-off3")
	first := rankList(t, env, "?period=all&limit=1&offset=0")
	second := rankList(t, env, "?period=all&limit=1&offset=1")
	if len(first) != 1 || first[0].FileName == nil || *first[0].FileName != "b.jpg" {
		t.Fatalf("offset=0 期望热度最高的 b.jpg，得到 %v", first)
	}
	if len(second) != 1 || second[0].FileName == nil || *second[0].FileName != "c.mp4" {
		t.Fatalf("offset=1 期望次高的 c.mp4，得到 %v", second)
	}
	if got := rankList(t, env, "?period=all&limit=1&offset=50"); len(got) != 0 {
		t.Errorf("offset=50（深越界）期望空数组，得到 %d 条", len(got))
	}
}
