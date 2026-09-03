package httpapi

// 观看历史端点（M3，DOMAIN_RULES §8 浏览面）端到端测试：
// 每资产一条（取 MAX open 时间）、按最近浏览时间倒序、keyset 分页
// cursor 可用、默认排除 COS 作者关联文件（includeCos=true 包含）。

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// getHistory 拉历史并解码。
func getHistory(t *testing.T, env *testEnv, query string) gen.HistoryPage {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/history"+query, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("历史期望 200，得到 %d（%s）", resp.StatusCode, query)
	}
	var page gen.HistoryPage
	if err := json.NewDecoder(resp.Body).Decode(&page); err != nil {
		t.Fatalf("解析历史失败: %v", err)
	}
	return page
}

// reportOpenAt 上报一条指定时刻的 open 事件（startedAt 直接来自 POST body）。
func reportOpenAt(t *testing.T, env *testEnv, assetID, startedAt, session string) {
	t.Helper()
	resp := env.do(t, http.MethodPost, "/api/v1/events/view",
		`{"assetId":"`+assetID+`","kind":"open","startedAt":"`+startedAt+`","sessionId":"`+session+`"}`)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("open 上报期望 202，得到 %d", resp.StatusCode)
	}
}

// TestHistoryOrderedOnePerAssetWithCursor：每资产一条（MAX 时间）、
// 时间倒序、limit=2 分页 cursor 可续读下一页。
func TestHistoryOrderedOnePerAssetWithCursor(t *testing.T) {
	env := newTestEnv(t)
	a, b, c := testFiles[0], testFiles[1], testFiles[2]

	// a: 20 日；b: 21 日 11:00；c: 19 日 + 21 日 12:00（MAX 取 21 日 12:00）
	reportOpenAt(t, env, a.id, "2026-08-20T10:00:00Z", "h-s1")
	reportOpenAt(t, env, b.id, "2026-08-21T11:00:00Z", "h-s2")
	reportOpenAt(t, env, c.id, "2026-08-19T09:00:00Z", "h-s3")
	reportOpenAt(t, env, c.id, "2026-08-21T12:00:00Z", "h-s4")

	// 返工补测：HistoryItem 必须带 durationMs（已看完/时长徽标数据源）。
	// fixture 插入不带时长，这里直接 UPDATE 视频资产模拟探测结果。
	if _, err := env.conn.Exec("UPDATE assets SET duration_ms = 90000 WHERE asset_id = ?", c.id); err != nil {
		t.Fatalf("设置视频时长失败: %v", err)
	}

	page := getHistory(t, env, "?limit=2")
	items := deref(page.Items)
	if len(items) != 2 {
		t.Fatalf("第一页应 2 条，得到 %d", len(items))
	}
	// 顺序（最近在前）：c(21 12:00 MAX) → b(21 11:00) → a(20 10:00)
	if *items[0].FileName != "c.mp4" || *items[1].FileName != "b.jpg" {
		t.Fatalf("第一页顺序错误：%s %s", *items[0].FileName, *items[1].FileName)
	}
	wantMillis := time.Date(2026, 8, 21, 12, 0, 0, 0, time.UTC).UnixMilli()
	if *items[0].LastViewedAt != wantMillis {
		t.Fatalf("c.mp4 lastViewedAt 期望 %d，得到 %d", wantMillis, *items[0].LastViewedAt)
	}
	if items[0].DurationMs == nil || *items[0].DurationMs != 90000 {
		t.Fatalf("视频资产应带 durationMs=90000，得到 %v", items[0].DurationMs)
	}
	if page.NextCursor == nil || *page.NextCursor == "" {
		t.Fatal("第一页应返回 nextCursor")
	}

	page2 := getHistory(t, env, "?limit=2&cursor="+*page.NextCursor)
	items2 := deref(page2.Items)
	if len(items2) != 1 || *items2[0].FileName != "a.jpg" {
		t.Fatalf("第二页应只剩 a.jpg，得到 %v", page2.Items)
	}
	if page2.NextCursor != nil {
		t.Fatal("末页不应有 nextCursor")
	}
}

// TestHistoryCosExclusion：默认排除 COS 作者关联文件（与 /assets 同口径），
// includeCos=true 重新包含。
func TestHistoryCosExclusion(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]

	// 给 a.jpg 挂 COS 作者（authors.type='cos'，COS 目录扫描产物同形态）。
	if err := env.q.UpsertAuthor(context.Background(), db.UpsertAuthorParams{
		ID: "cos_test_author", DisplayName: "COS作者", Type: "cos",
		CreatedAt: store.FormatTimestamp(env.clock.Now()),
	}); err != nil {
		t.Fatalf("建 COS 作者失败: %v", err)
	}
	if err := env.q.AddAssetAuthor(context.Background(), db.AddAssetAuthorParams{
		AssetID: a.id, AuthorID: "cos_test_author",
	}); err != nil {
		t.Fatalf("关联失败: %v", err)
	}
	reportOpenAt(t, env, a.id, "2026-08-21T10:00:00Z", "cos-s1")

	page := getHistory(t, env, "")
	if n := len(deref(page.Items)); n != 0 {
		t.Fatalf("默认应排除 COS 关联资产，得到 %d 条", n)
	}
	page = getHistory(t, env, "?includeCos=true")
	items := deref(page.Items)
	if len(items) != 1 || *items[0].FileName != "a.jpg" {
		t.Fatalf("includeCos=true 应包含 a.jpg，得到 %v", page.Items)
	}
}

// TestHistoryDeletedAssetExcluded：view_events 保留已删资产事件（ADR-0005），
// 但历史端点 INNER JOIN assets 应排除它们。
func TestHistoryDeletedAssetExcluded(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	reportOpenAt(t, env, a.id, "2026-08-21T10:00:00Z", "del-s1")

	// 直接删资产记录（模拟物理删除；回收站路径在实现层，这里验查询口径）。
	res, err := env.conn.Exec("DELETE FROM assets WHERE asset_id = ?", a.id)
	if err != nil || res == nil {
		t.Fatalf("删资产失败: %v", err)
	}
	page := getHistory(t, env, "")
	if n := len(deref(page.Items)); n != 0 {
		t.Fatalf("已删资产不应出现在历史，得到 %d 条", n)
	}
}
