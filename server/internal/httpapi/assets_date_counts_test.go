package httpapi

// assets_date_counts_test.go：GET /assets 的 dateCounts / tzOffsetMinutes
// 协议面（2026-10-10 加）端到端用例。为什么需要这一对参数：相册页按
// modifiedAt 折叠日期组头（DOMAIN_RULES §8），分页只加载前若干条时组头
// 计数原本只能显示「已加载条数」——同一天文件多时数字随滚动一路跳增，
// 第一眼看到的就是错的（2026-10-09 真机实测：今天 120→125、周三 66→282）。
// 本测试锁定四件事：
//  1. 按**客户端本地日历日**分桶（同一 UTC 日跨两个本地日的资产各归其桶）；
//  2. 缺省（不传 tzOffsetMinutes）保持既有 UTC 日口径——向后兼容是加参数
//     而非改参数的前提，老客户端行为逐字节不变；
//  3. dateFrom/dateTo 的本地日解释随 tzOffsetMinutes 变化（既有「时间」筛选
//     按 UTC 日解释、与客户端本地日分组差 8 小时的偏差修复面）；
//  4. dateCounts 仅首屏返回、未请求时字段省略、越界 tz 400 INVALID_PARAM。
//
// 数据直接经 store 层构造（不经扫描器）：本测试锁的是聚合口径，不是扫描链路。
// fixture：newTestEnv 的假扫描器给 3 个固定 mtime 资产（a.jpg 2026-08-20T10:00Z、
// b.jpg 08-21T10:00Z、c.mp4 08-22T10:00Z，UTC 与 UTC+8 同日落桶，不干扰
// 10 月边界断言）；本测试另加 4 条横跨 UTC 日界的资产。

import (
	"context"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedDateCountBoundaryAssets 加 4 条资产，专门踩 UTC+8 本地日的两个边界：
//
//	e1 2026-10-08T16:00:00Z = 本地 10-09 00:00:00（UTC 日 10-08）
//	e2 2026-10-09T15:59:59Z = 本地 10-09 23:59:59（UTC 日 10-09）
//	e3 2026-10-09T16:00:00Z = 本地 10-10 00:00:00（UTC 日 10-09）
//	e4 2026-10-09T20:00:00Z = 本地 10-10 04:00:00（UTC 日 10-09）
//
// 于是 tz=+480 下 10-09 与 10-10 各 2 条，tz=0 下 10-09 三条 + 10-08 一条，
// 三个口径的数字互不相同——任一实现把日界算错都会立刻暴露。
func seedDateCountBoundaryAssets(t *testing.T, e *testEnv) {
	t.Helper()
	ctx := context.Background()
	for i, spec := range []struct {
		rel  string
		name string
		kind string
		utc  time.Time
	}{
		{"e1.jpg", "e1.jpg", "image", time.Date(2026, 10, 8, 16, 0, 0, 0, time.UTC)},
		{"e2.jpg", "e2.jpg", "image", time.Date(2026, 10, 9, 15, 59, 59, 0, time.UTC)},
		{"e3.jpg", "e3.jpg", "image", time.Date(2026, 10, 9, 16, 0, 0, 0, time.UTC)},
		{"e4.mp4", "e4.mp4", "video", time.Date(2026, 10, 9, 20, 0, 0, 0, time.UTC)},
	} {
		stamp := store.FormatTimestamp(spec.utc)
		if _, err := e.q.UpsertAsset(ctx, db.UpsertAssetParams{
			AssetID: uuid.NewString(), LibraryID: e.libID, RelPath: spec.rel,
			FileName: spec.name, MediaType: spec.kind, SizeBytes: int64(100 + i),
			Mtime: stamp, CreatedAt: stamp, UpdatedAt: stamp,
		}); err != nil {
			t.Fatalf("UpsertAsset %s 失败: %v", spec.rel, err)
		}
	}
}

// fetchAssetPage 取一页 GET /assets（带 token），断言 200 并解析。
func fetchAssetPage(t *testing.T, e *testEnv, query string) gen.AssetPage {
	t.Helper()
	resp := e.do(t, "GET", "/api/v1/assets"+query, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("%s 期望 200，得到 %d", query, resp.StatusCode)
	}
	defer closeBody(resp)
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("%s 解析失败: %v", query, err)
	}
	return page
}

// dateCountMap 把 dateCounts 折成 date→fileCount，顺带断言降序与无重复日。
func dateCountMap(t *testing.T, query string, page gen.AssetPage) map[string]int {
	t.Helper()
	if page.DateCounts == nil {
		t.Fatalf("%s 应返回 dateCounts", query)
	}
	out := make(map[string]int, len(*page.DateCounts))
	prev := ""
	for _, b := range *page.DateCounts {
		if b.Date == "" {
			t.Fatalf("%s 出现空日期分桶", query)
		}
		if prev != "" && b.Date >= prev {
			t.Fatalf("%s dateCounts 应按日期降序且不重复，得到 %q 在 %q 之后", query, b.Date, prev)
		}
		prev = b.Date
		if _, dup := out[b.Date]; dup {
			t.Fatalf("%s 出现重复日期 %s", query, b.Date)
		}
		out[b.Date] = b.FileCount
	}
	return out
}

// TestAssetDateCountsLocalDayBuckets：本地日分桶 + UTC 缺省口径 + 时区负偏移。
func TestAssetDateCountsLocalDayBuckets(t *testing.T) {
	env := newTestEnv(t)
	seedDateCountBoundaryAssets(t, env)

	// ① tz=+480（东八区）：10-10 两条（e3/e4）、10-09 两条（e1/e2）；
	//    三个 8 月资产 UTC 与本地同日落桶，各 1 条。
	q := "?dateCounts=true&tzOffsetMinutes=480"
	got := dateCountMap(t, q, fetchAssetPage(t, env, q))
	want := map[string]int{
		"2026-10-10": 2, "2026-10-09": 2,
		"2026-08-22": 1, "2026-08-21": 1, "2026-08-20": 1,
	}
	if len(got) != len(want) {
		t.Fatalf("tz=+480 应 %d 个桶，得到 %v", len(want), got)
	}
	for day, n := range want {
		if got[day] != n {
			t.Fatalf("tz=+480 日 %s 应 %d 条，得到 %d（全量 %v）", day, n, got[day], got)
		}
	}

	// ② 缺省不传 tzOffsetMinutes：既有 UTC 日口径（10-09 三条 = e2/e3/e4）。
	q = "?dateCounts=true"
	got = dateCountMap(t, q, fetchAssetPage(t, env, q))
	wantUTC := map[string]int{
		"2026-10-09": 3, "2026-10-08": 1,
		"2026-08-22": 1, "2026-08-21": 1, "2026-08-20": 1,
	}
	if len(got) != len(wantUTC) {
		t.Fatalf("tz 缺省（UTC）应 %d 个桶，得到 %v", len(wantUTC), got)
	}
	for day, n := range wantUTC {
		if got[day] != n {
			t.Fatalf("tz 缺省日 %s 应 %d 条，得到 %d（全量 %v）", day, n, got[day], got)
		}
	}

	// ③ tz=-300（西五区）：e1 落 10-08，e2/e3/e4 全落 10-09。
	q = "?dateCounts=true&tzOffsetMinutes=-300"
	got = dateCountMap(t, q, fetchAssetPage(t, env, q))
	if got["2026-10-09"] != 3 || got["2026-10-08"] != 1 || len(got) != 5 {
		t.Fatalf("tz=-300 应 10-09=3 / 10-08=1 共 5 桶，得到 %v", got)
	}

	// ④ 分桶与 totalMatched 同筛选矩阵：桶内计数之和 == 总数（分桶不丢行）。
	page := fetchAssetPage(t, env, "?dateCounts=true&tzOffsetMinutes=480&limit=200")
	sum := 0
	for _, n := range dateCountMap(t, "tz=+480", page) {
		sum += n
	}
	if page.TotalMatched == nil || sum != *page.TotalMatched {
		t.Fatalf("分桶之和应等于 totalMatched，得到 %d / %v", sum, page.TotalMatched)
	}
}

// TestAssetDateCountsRespectsFilters：分桶随筛选矩阵收窄（与列表同口径）。
func TestAssetDateCountsRespectsFilters(t *testing.T) {
	env := newTestEnv(t)
	seedDateCountBoundaryAssets(t, env)

	// mediaType=video：只剩 c.mp4（8-22）与 e4.mp4（本地 10-10）。
	q := "?dateCounts=true&tzOffsetMinutes=480&mediaType=video"
	got := dateCountMap(t, q, fetchAssetPage(t, env, q))
	if len(got) != 2 || got["2026-08-22"] != 1 || got["2026-10-10"] != 1 {
		t.Fatalf("mediaType=video 应 {08-22:1, 10-10:1}，得到 %v", got)
	}

	// 与列表同口径的收窄校验：分桶之和 == 同筛选下的 totalMatched。
	page := fetchAssetPage(t, env, q+"&limit=200")
	sum := 0
	for _, n := range dateCountMap(t, q, page) {
		sum += n
	}
	if page.TotalMatched == nil || sum != *page.TotalMatched {
		t.Fatalf("video 分桶之和应等于 totalMatched，得到 %d / %v", sum, page.TotalMatched)
	}
}

// TestAssetDateFromToLocalDaySemantics：dateFrom/dateTo 按 tzOffsetMinutes
// 解释为客户端本地日（缺省 UTC 不变）——同一对日期参数在两种口径下命中
// 不同条数，锁死「时间筛选不再偏 8 小时」的修复面。
func TestAssetDateFromToLocalDaySemantics(t *testing.T) {
	env := newTestEnv(t)
	seedDateCountBoundaryAssets(t, env)

	// 本地 10-09 一整天（UTC+8）= e1 + e2 = 2 条；e3/e4 已跨到本地 10-10。
	q := "?dateFrom=2026-10-09&dateTo=2026-10-09&tzOffsetMinutes=480"
	page := fetchAssetPage(t, env, q)
	if page.TotalMatched == nil || *page.TotalMatched != 2 {
		t.Fatalf("本地日 10-09 应 2 条，得到 %v", page.TotalMatched)
	}

	// 同一对日期按 UTC 日解释（缺省）= e2 + e3 + e4 = 3 条。
	page = fetchAssetPage(t, env, "?dateFrom=2026-10-09&dateTo=2026-10-09")
	if page.TotalMatched == nil || *page.TotalMatched != 3 {
		t.Fatalf("UTC 日 10-09 应 3 条，得到 %v", page.TotalMatched)
	}
}

// TestAssetDateCountsFirstPageOnly：dateCounts 只在首屏计算——翻页请求
// 不再回填（分桶是筛选态的函数，翻页不改变它，重复查是纯浪费）。
func TestAssetDateCountsFirstPageOnly(t *testing.T) {
	env := newTestEnv(t)
	seedDateCountBoundaryAssets(t, env)

	first := fetchAssetPage(t, env, "?dateCounts=true&tzOffsetMinutes=480&limit=1")
	if first.NextCursor == nil || *first.NextCursor == "" {
		t.Fatal("limit=1 应返回 nextCursor")
	}
	if first.DateCounts == nil {
		t.Fatal("首屏应返回 dateCounts")
	}
	next := fetchAssetPage(t, env, "?dateCounts=true&tzOffsetMinutes=480&limit=1&cursor="+*first.NextCursor)
	if next.DateCounts != nil {
		t.Fatalf("翻页不应回填 dateCounts，得到 %v", *next.DateCounts)
	}
	if next.TotalMatched != nil {
		t.Fatalf("翻页不应回填 totalMatched，得到 %v", *next.TotalMatched)
	}
}

// TestAssetDateCountsOptInAndValidation：未请求时字段省略；越界 tz 400。
func TestAssetDateCountsOptInAndValidation(t *testing.T) {
	env := newTestEnv(t)

	if page := fetchAssetPage(t, env, ""); page.DateCounts != nil {
		t.Fatalf("未请求 dateCounts 时字段应省略，得到 %v", *page.DateCounts)
	}
	// 显式 false 同样不返回（协议 default=false）。
	if page := fetchAssetPage(t, env, "?dateCounts=false"); page.DateCounts != nil {
		t.Fatalf("dateCounts=false 时字段应省略，得到 %v", *page.DateCounts)
	}
	// 越界时区偏移（±14h 之外）→ 400 INVALID_PARAM（openapi 的
	// minimum/maximum 生成器不校验，服务端兜底）。
	for _, q := range []string{"?tzOffsetMinutes=900", "?tzOffsetMinutes=-900"} {
		resp := env.do(t, "GET", "/api/v1/assets"+q, "")
		var errResp gen.Error
		_ = decodeBody(resp, &errResp)
		closeBody(resp)
		if resp.StatusCode != http.StatusBadRequest || errResp.Code != codeInvalidParam {
			t.Fatalf("%s 期望 400 INVALID_PARAM，得到 %d %q", q, resp.StatusCode, errResp.Code)
		}
	}
	// 边界值 ±840 合法（不因校验误伤端点）。
	for _, q := range []string{"?dateCounts=true&tzOffsetMinutes=840", "?dateCounts=true&tzOffsetMinutes=-840"} {
		if page := fetchAssetPage(t, env, q); page.DateCounts == nil {
			t.Fatalf("%s 边界值应放行并返回 dateCounts", q)
		}
	}
}
