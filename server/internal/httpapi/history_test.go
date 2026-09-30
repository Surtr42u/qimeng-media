package httpapi

// 观看历史端点（M3，DOMAIN_RULES §8 浏览面）端到端测试：
// 每资产一条（取 MAX open 时间）、按最近浏览时间倒序、keyset 分页
// cursor 可续读下一页；COS 分区缺省全部（2026-09-05 用户拍板），
// includeCos=false 切常规、cosOnly=true 只 COS；mediaType/work/character
// 筛选与 GET /assets 同语义。

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/authoring"
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

// TestHistoryCosDefaultAllAndSwitches：COS 分区三态（2026-09-05 用户拍板，
// 原缺省排除口径废止）——缺省全部（常规∪COS 合并）、includeCos=false 切
// 常规（排除 COS）、cosOnly=true 只 COS（且与 includeCos 同真时优先）。
func TestHistoryCosDefaultAllAndSwitches(t *testing.T) {
	env := newTestEnv(t)
	a, b := testFiles[0], testFiles[1]

	// a.jpg 挂 COS 作者（authors.type='cos'，COS 目录扫描产物同形态）；
	// b.jpg 保持常规。两者都造 open 事件。
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
	reportOpenAt(t, env, b.id, "2026-08-20T10:00:00Z", "cos-s2")

	names := func(query string) []string {
		t.Helper()
		items := deref(getHistory(t, env, query).Items)
		out := make([]string, 0, len(items))
		for _, it := range items {
			out = append(out, *it.FileName)
		}
		return out
	}
	// 缺省（2026-09-05 用户拍板）：常规∪COS 合并，两资产都出现。
	if got := names(""); len(got) != 2 {
		t.Fatalf("缺省应为全部（含 COS），得到 %v", got)
	}
	// includeCos=false：切常规分区，只剩 b.jpg。
	if got := names("?includeCos=false"); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("includeCos=false 应只含 b.jpg，得到 %v", got)
	}
	// cosOnly=true：切 COS 分区，只剩 a.jpg。
	if got := names("?cosOnly=true"); len(got) != 1 || got[0] != "a.jpg" {
		t.Fatalf("cosOnly=true 应只含 a.jpg，得到 %v", got)
	}
	// cosOnly 与 includeCos 同真：cosOnly 优先。
	if got := names("?includeCos=true&cosOnly=true"); len(got) != 1 || got[0] != "a.jpg" {
		t.Fatalf("cosOnly 优先应只含 a.jpg，得到 %v", got)
	}
}

// TestHistoryFilters：/history 新增筛选——mediaType / work / character
// （'a+b' 全部命中语义），与缺省含 COS 的子集叠加生效。
func TestHistoryFilters(t *testing.T) {
	env := newTestEnv(t)
	a, b, c := testFiles[0], testFiles[1], testFiles[2]
	ctx := context.Background()
	now := store.FormatTimestamp(env.clock.Now())

	// b.jpg：挂 COS 作者 + cos_work=作品P + 双角色 天使+黑百合。cos_work 不在
	// UpsertAsset 的 DO UPDATE 列里（migration 0008 由扫描器路径维护，测试
	// 不走扫描链路），用与 duration_ms 用例同款的直改 UPDATE 写入。
	cosID := authoring.GenerateCosAuthorID("历史酱")
	if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: cosID, DisplayName: "历史酱", Type: authoring.AuthorTypeCos, CreatedAt: now,
	}); err != nil {
		t.Fatalf("建 COS 作者失败: %v", err)
	}
	if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: b.id, AuthorID: cosID}); err != nil {
		t.Fatalf("关联 COS 作者失败: %v", err)
	}
	if _, err := env.conn.Exec("UPDATE assets SET cos_work = ? WHERE asset_id = ?", "作品P", b.id); err != nil {
		t.Fatalf("写 cos_work 失败: %v", err)
	}
	// a.jpg：单角色 天使；b.jpg：双角色（'a+b' 拆分后须全部命中）。
	for _, ac := range []db.AddAssetCharacterParams{
		{AssetID: a.id, CharacterName: "天使"},
		{AssetID: b.id, CharacterName: "天使"},
		{AssetID: b.id, CharacterName: "黑百合"},
	} {
		if err := env.q.AddAssetCharacter(ctx, ac); err != nil {
			t.Fatalf("挂角色失败: %v", err)
		}
	}
	reportOpenAt(t, env, a.id, "2026-08-23T10:00:00Z", "f-s1")
	reportOpenAt(t, env, b.id, "2026-08-23T11:00:00Z", "f-s2")
	reportOpenAt(t, env, c.id, "2026-08-23T12:00:00Z", "f-s3")

	names := func(query string) []string {
		t.Helper()
		items := deref(getHistory(t, env, query).Items)
		out := make([]string, 0, len(items))
		for _, it := range items {
			out = append(out, *it.FileName)
		}
		return out
	}
	// 缺省：三资产全部在历史（含 COS 的 b.jpg——2026-09-05 拍板缺省全部）。
	if got := names(""); len(got) != 3 {
		t.Fatalf("缺省应含全部三条（含 COS），得到 %v", got)
	}
	// mediaType=video：只剩 c.mp4。
	if got := names("?mediaType=video"); len(got) != 1 || got[0] != "c.mp4" {
		t.Fatalf("mediaType=video 应只含 c.mp4，得到 %v", got)
	}
	// work=作品P：只剩 b.jpg（COS 作品筛选）。
	if got := names("?work=" + percentEncode("作品P")); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("work=作品P 应只含 b.jpg，得到 %v", got)
	}
	// character=天使+黑百合：全部命中语义 → 只剩 b.jpg（a.jpg 缺黑百合）。
	if got := names("?character=" + percentEncode("天使+黑百合")); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("character=天使+黑百合 应只含 b.jpg，得到 %v", got)
	}
	// character=黑百合 单角色：b.jpg（a.jpg 无此角色）。
	if got := names("?character=" + percentEncode("黑百合")); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("character=黑百合 应只含 b.jpg，得到 %v", got)
	}
	// 组合：cosOnly=true&mediaType=image → 只剩 b.jpg（COS 且图片）。
	if got := names("?cosOnly=true&mediaType=image"); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("cosOnly+image 应只含 b.jpg，得到 %v", got)
	}

	// ---- 2026-09-09 协议批：source 多值 + authorId 过滤（#30） ----
	// a.jpg 回写 source=site-a；c.mp4 保持 NULL（'其他' 桶）。
	if _, err := env.conn.Exec("UPDATE assets SET source = 'site-a' WHERE asset_id = ?", a.id); err != nil {
		t.Fatalf("回写 source 失败: %v", err)
	}
	// a.jpg 挂常规作者。
	if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: "hist_reg_author", DisplayName: "历史画师", Type: authoring.AuthorTypeRegular, CreatedAt: now,
	}); err != nil {
		t.Fatalf("建常规作者失败: %v", err)
	}
	if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: a.id, AuthorID: "hist_reg_author"}); err != nil {
		t.Fatalf("关联作者失败: %v", err)
	}
	// source=site-a：只剩 a.jpg。
	if got := names("?source=site-a"); len(got) != 1 || got[0] != "a.jpg" {
		t.Fatalf("source=site-a 应只含 a.jpg，得到 %v", got)
	}
	// source 双值 OR：site-a ∪ 其他（c.mp4 无出处）→ a + c。
	if got := names("?source=site-a&source=" + percentEncode("其他")); len(got) != 2 {
		t.Fatalf("source 双值(site-a,其他) 应 2 条，得到 %v", got)
	}
	// source 单值兼容已在上一断言覆盖（单元素数组）；authorId 过滤 → 只 a.jpg。
	if got := names("?authorId=hist_reg_author"); len(got) != 1 || got[0] != "a.jpg" {
		t.Fatalf("authorId 应只含 a.jpg，得到 %v", got)
	}
	// 跨维 AND：source=其他 ∩ authorId → 空（a 有出处、c 无作者）。
	if got := names("?source=" + percentEncode("其他") + "&authorId=hist_reg_author"); len(got) != 0 {
		t.Fatalf("其他∩authorId 应为空，得到 %v", got)
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
