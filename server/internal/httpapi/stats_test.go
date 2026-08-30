package httpapi

// M3 统计端点测试：总览口径（animated_image 计入 imageCount、浏览计数
// 直接数事件流）+ 趋势各 range（200 / 空库空桶 / label 抽查 / 窗口守恒）。
// 趋势造数直接写 asset_daily_stats 物化行（端点只读物化表，DOMAIN_RULES
// §5 同源口径）；日期全部相对测试钟（clock=2026-08-22）推导，与时区无关。
//
// 各 range 的守恒期望不同是有意的：造数横跨窗外（-100 天 / 2024-01），
// 用于锁定「窗口裁剪后桶之和 = 窗口内行之和」——day(30 天) 收 2 行、
// week(12 周) 收 2 行（-100 天在窗外）、month(24 月)/quarter(8 季) 收 3 行
// （2024-01 在窗外）、year(5 年)/all 收全部 4 行。

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// todayLocal 是测试钟的本地日历日（各用例共用的「今天」）。
func todayLocal(env *testEnv) time.Time {
	return env.clock.Now().Local()
}

// postViewEvent 上报一条浏览事件。
func postViewEvent(t *testing.T, env *testEnv, assetID string, kind gen.ViewEventReportKind, session string, at time.Time) {
	t.Helper()
	body, err := json.Marshal(gen.ViewEventReport{
		AssetId:   uuid.MustParse(assetID),
		Kind:      kind,
		SessionId: session,
		StartedAt: at,
	})
	if err != nil {
		t.Fatalf("构造事件请求失败: %v", err)
	}
	resp := env.do(t, http.MethodPost, "/api/v1/events/view", string(body))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("事件上报期望 202，得到 %d", resp.StatusCode)
	}
}

// seedDailyRow 直接种一行物化聚合（趋势端点的数据源）。
func seedDailyRow(t *testing.T, env *testEnv, assetID, day string, view, play, secs int64) {
	t.Helper()
	if err := env.q.UpsertAssetDailyStats(context.Background(), db.UpsertAssetDailyStatsParams{
		AssetID: assetID, Day: day, ViewCount: view, PlayCount: play, BrowseSeconds: secs,
	}); err != nil {
		t.Fatalf("种物化行失败: %v", err)
	}
}

// getTrends 请求趋势端点并解码。
func getTrends(t *testing.T, env *testEnv, query string) []gen.TrendBucket {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/stats/trends"+query, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("趋势期望 200，得到 %d（%s）", resp.StatusCode, query)
	}
	var buckets []gen.TrendBucket
	if err := json.NewDecoder(resp.Body).Decode(&buckets); err != nil {
		t.Fatalf("解析趋势失败: %v", err)
	}
	return buckets
}

// getOverview 请求总览端点并解码。
func getOverview(t *testing.T, env *testEnv) gen.StatsOverview {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/stats/overview", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("总览期望 200，得到 %d", resp.StatusCode)
	}
	var o gen.StatsOverview
	if err := json.NewDecoder(resp.Body).Decode(&o); err != nil {
		t.Fatalf("解析总览失败: %v", err)
	}
	return o
}

// TestStatsOverviewCounts：库形态来自 assets 聚合、浏览计数来自事件流。
// 前置：newTestEnv 的 3 资产（a.jpg/b.jpg image、c.mp4 video）。
func TestStatsOverviewCounts(t *testing.T) {
	env := newTestEnv(t)
	a, c := testFiles[0], testFiles[2]

	// 动图计入 imageCount 口径（DOMAIN_RULES §11：gif 单列 animated_image）
	animID := uuid.NewString()
	now := store.FormatTimestamp(env.clock.Now())
	if _, err := env.q.UpsertAsset(context.Background(), db.UpsertAssetParams{
		AssetID: animID, LibraryID: env.libID, RelPath: "anim.gif", FileName: "anim.gif",
		MediaType: "animated_image", SizeBytes: 1234, Mtime: now, CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("插入动图资产失败: %v", err)
	}

	// 今日 2 次 open（不同会话各计 1）
	postViewEvent(t, env, a.id, gen.Open, "ov-s1", env.clock.Now())
	postViewEvent(t, env, a.id, gen.Open, "ov-s2", env.clock.Now())
	// 昨天 1 次 open（计入 totalViews，不计入 todayViews）
	postViewEvent(t, env, c.id, gen.Open, "ov-s3", env.clock.Now().Add(-24*time.Hour))

	o := getOverview(t, env)
	if o.TotalFiles == nil || *o.TotalFiles != 4 {
		t.Fatalf("totalFiles 期望 4，得到 %v", o.TotalFiles)
	}
	// image + animated_image 都计入 imageCount
	if o.ImageCount == nil || *o.ImageCount != 3 {
		t.Fatalf("imageCount 期望 3（image 2 + animated_image 1），得到 %v", o.ImageCount)
	}
	if o.VideoCount == nil || *o.VideoCount != 1 {
		t.Fatalf("videoCount 期望 1，得到 %v", o.VideoCount)
	}
	wantSize := testFiles[0].size + testFiles[1].size + testFiles[2].size + 1234
	if o.TotalSizeBytes == nil || *o.TotalSizeBytes != wantSize {
		t.Fatalf("totalSizeBytes 期望 %d，得到 %v", wantSize, o.TotalSizeBytes)
	}
	if o.TodayViews == nil || *o.TodayViews != 2 {
		t.Fatalf("todayViews 期望 2，得到 %v", o.TodayViews)
	}
	if o.TotalViews == nil || *o.TotalViews != 3 {
		t.Fatalf("totalViews 期望 3，得到 %v", o.TotalViews)
	}
}

// TestStatsTrendsRanges：六个 range 全部 200，label/窗口/守恒抽查。
func TestStatsTrendsRanges(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	today := todayLocal(env)

	seedDailyRow(t, env, a.id, store.FormatDay(today), 2, 1, 30)                    // 今天：3
	seedDailyRow(t, env, a.id, store.FormatDay(today.AddDate(0, 0, -3)), 1, 0, 0)   // 近 3 天：1
	seedDailyRow(t, env, a.id, store.FormatDay(today.AddDate(0, 0, -100)), 1, 0, 0) // >12 周外：1
	seedDailyRow(t, env, a.id, "2024-01-15", 7, 0, 0)                               // 远超 24 月：7

	// day：窗口 30 天收 2 行（和 4），从 -3 天铺到今天共 4 个天桶
	dayBuckets := getTrends(t, env, "?range=day")
	if len(dayBuckets) != 4 {
		t.Fatalf("range=day 期望 4 个天桶，得到 %d", len(dayBuckets))
	}
	if want := today.AddDate(0, 0, -3).Format("01/02"); *dayBuckets[0].Label != want {
		t.Fatalf("day 首桶 label 期望 %s，得到 %s", want, *dayBuckets[0].Label)
	}
	if *dayBuckets[3].ViewCount != 2 || *dayBuckets[3].PlayCount != 1 {
		t.Fatalf("day 末桶（今天）值错误：%+v", dayBuckets[3])
	}
	if sum := trendViewPlaySum(dayBuckets); sum != 4 {
		t.Fatalf("range=day 桶之和期望 4，得到 %d", sum)
	}

	// week：窗口 12 周收同上 2 行（-100 天在窗外），同周造数 → 1 数据桶 + 前导 0
	weekBuckets := getTrends(t, env, "?range=week")
	if sum := trendViewPlaySum(weekBuckets); sum != 4 {
		t.Fatalf("range=week 桶之和期望 4，得到 %d", sum)
	}
	if weekBuckets[0].ViewCount != nil && *weekBuckets[0].ViewCount+*weekBuckets[0].PlayCount != 0 {
		t.Fatalf("range=week 首桶应为前导 0 桶：%+v", weekBuckets[0])
	}

	// month：窗口 24 月收 3 行（2024-01 窗外），-100 天 ~ 今天 → 按月分桶
	monthBuckets := getTrends(t, env, "?range=month")
	if sum := trendViewPlaySum(monthBuckets); sum != 5 {
		t.Fatalf("range=month 桶之和期望 5，得到 %d", sum)
	}
	if label := *monthBuckets[0].Label; !strings.HasSuffix(label, "月") {
		t.Fatalf("range=month 首桶 label 应为 MM月 形态，得到 %s", label)
	}

	// quarter：窗口 8 季收 3 行（2024-01 在窗外）→ 26/Q2 与 26/Q3 两桶
	quarterBuckets := getTrends(t, env, "?range=quarter")
	if sum := trendViewPlaySum(quarterBuckets); sum != 5 {
		t.Fatalf("range=quarter 桶之和期望 5，得到 %d", sum)
	}
	if *quarterBuckets[0].Label != "26/Q2" || *quarterBuckets[len(quarterBuckets)-1].Label != "26/Q3" {
		t.Fatalf("range=quarter label 抽查失败：%+v", quarterBuckets)
	}

	// year：窗口 5 年收全部 4 行 → 2024/2025/2026 三个年桶
	yearBuckets := getTrends(t, env, "?range=year")
	if sum := trendViewPlaySum(yearBuckets); sum != 12 {
		t.Fatalf("range=year 桶之和期望 12，得到 %d", sum)
	}
	if len(yearBuckets) != 3 || *yearBuckets[0].Label != "2024" || *yearBuckets[2].Label != "2026" {
		t.Fatalf("range=year 分桶结构错误：%v", yearBuckets)
	}

	// all：全跨度动态粒度（跨度 > 24 月 → 按季），首桶 24/Q1，总和守恒 12
	allBuckets := getTrends(t, env, "?range=all")
	if *allBuckets[0].Label != "24/Q1" {
		t.Fatalf("range=all 首桶期望 24/Q1，得到 %s", *allBuckets[0].Label)
	}
	if sum := trendViewPlaySum(allBuckets); sum != 12 {
		t.Fatalf("range=all 桶之和期望守恒 12，得到 %d", sum)
	}
}

// trendViewPlaySum 桶序列的 view+play 总和（守恒断言用）。
func trendViewPlaySum(buckets []gen.TrendBucket) int64 {
	var sum int64
	for _, b := range buckets {
		if b.ViewCount != nil {
			sum += int64(*b.ViewCount)
		}
		if b.PlayCount != nil {
			sum += int64(*b.PlayCount)
		}
	}
	return sum
}

// TestStatsTrendsEmptyLibrary：空库（无造数）→ 200 + 空数组（非 null，
// 前端「暂无趋势数据」空态依赖数组语义）。
func TestStatsTrendsEmptyLibrary(t *testing.T) {
	env := newTestEnv(t)
	for _, q := range []string{"day", "week", "month", "quarter", "year", "all"} {
		buckets := getTrends(t, env, "?range="+q)
		if buckets == nil {
			t.Fatalf("range=%s 空数据应序列化为 [] 而非 null", q)
		}
		if len(buckets) != 0 {
			t.Fatalf("range=%s 空库期望 0 桶，得到 %d", q, len(buckets))
		}
	}
}

// TestStatsTrendsBadRequest：非法 range → 400。
func TestStatsTrendsBadRequest(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodGet, "/api/v1/stats/trends?range=decade", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("非法 range 期望 400，得到 %d", resp.StatusCode)
	}
}

// TestStatsTrendsMediaTypeFilter：mediaType 过滤只统计该类型的聚合行。
func TestStatsTrendsMediaTypeFilter(t *testing.T) {
	env := newTestEnv(t)
	a, c := testFiles[0], testFiles[2] // a=image，c=video
	today := todayLocal(env)
	seedDailyRow(t, env, a.id, store.FormatDay(today), 2, 0, 0)
	seedDailyRow(t, env, c.id, store.FormatDay(today), 5, 0, 0)

	buckets := getTrends(t, env, "?range=day&mediaType=video")
	if len(buckets) == 0 || *buckets[len(buckets)-1].ViewCount != 5 {
		t.Fatalf("mediaType=video 期望今天的桶 view=5，得到 %v", buckets)
	}
}
