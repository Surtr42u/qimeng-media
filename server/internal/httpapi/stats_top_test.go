package httpapi

// 统计聚合榜单与总览扩展字段测试（协议批 P2，2026-09-09）：
// most-viewed（views/seconds 两口径、窗口边界、limit 钳制、已删资产排除）、
// top-authors（含 COS 作者按 COS 资产计）、top-tags、trends source 过滤、
// overview 来源库存与 avgViewsPerFile（分母空 null）。口径见 DOMAIN_RULES
// §5「统计聚合榜单口径」。

import (
	"context"
	"database/sql"
	"encoding/json"
	"math"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedViewEvent 绕过 HTTP 直插事件（构造「引用不存在资产」的孤儿事件 /
// 带 NULL seconds 的 dwell 行用；正常上报路径走 postViewEvent〔stats_test.go〕
// 与 postDwellEvent〔engagement_test.go〕）。
func seedViewEvent(t *testing.T, env *testEnv, assetID, kind, session string, at time.Time, seconds int64) {
	t.Helper()
	if err := env.q.InsertViewEvent(context.Background(), db.InsertViewEventParams{
		AssetID: assetID, Kind: kind, SessionID: session,
		StartedAt: store.FormatTimestamp(at),
		Seconds:   sql.NullInt64{Int64: seconds, Valid: kind == "dwell"},
	}); err != nil {
		t.Fatalf("直插事件失败: %v", err)
	}
}

// seedAuthorFixture 建「作者 + 资产 + 关联」三元组：isCos 选作者类型
// （COS 作者落独立 COS 库资产，§6 前缀隔离），返回资产 ID。测试只需
// 关联关系成立，不做完整扫描模拟。
func seedAuthorFixture(t *testing.T, env *testEnv, authorName, assetRel string, isCos bool) string {
	t.Helper()
	ctx := context.Background()
	now := store.FormatTimestamp(time.Now())
	libID := env.libID
	if isCos {
		lib, err := env.q.CreateLibrary(ctx, db.CreateLibraryParams{
			ID: uuid.NewString(), Name: "COS库-" + authorName,
			RootPath: env.media + "/cos-" + authorName,
			Kind:     "cos", CreatedAt: now,
		})
		if err != nil {
			t.Fatalf("建 COS 库失败: %v", err)
		}
		libID = lib.ID
	}
	asset, err := env.q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: uuid.NewString(), LibraryID: libID, RelPath: assetRel,
		FileName: assetRel, MediaType: "image", SizeBytes: 10,
		Mtime: now, CreatedAt: now, UpdatedAt: now,
	})
	if err != nil {
		t.Fatalf("UpsertAsset %s 失败: %v", assetRel, err)
	}
	var authorID, authorType string
	if isCos {
		authorID, authorType = authoring.GenerateCosAuthorID(authorName), authoring.AuthorTypeCos
	} else {
		authorID, authorType = authoring.GenerateAuthorID(authorName), authoring.AuthorTypeRegular
	}
	if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: authorID, DisplayName: authorName, Type: authorType, CreatedAt: now,
	}); err != nil {
		t.Fatalf("UpsertAuthor %s 失败: %v", authorName, err)
	}
	if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: asset.AssetID, AuthorID: authorID,
	}); err != nil {
		t.Fatalf("AddAssetAuthor 失败: %v", err)
	}
	return asset.AssetID
}

// localDayShift 返回测试钟本地日历日偏移 n 天后的本地时刻（h:m:s）——
// 窗口边界用例用（窗口起点 = 今天-(N-1) 天 00:00，各 range 的 N 见
// stats.go 常量；time.Date 对跨月日偏移自动规范化）。
func localDayShift(env *testEnv, n int, h, m, s int) time.Time {
	base := todayLocal(env)
	return time.Date(base.Year(), base.Month(), base.Day()+n, h, m, s, 0, base.Location())
}

// getMostViewed 请求常看文件端点。
func getMostViewed(t *testing.T, env *testEnv, query string) []gen.MostViewedItem {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/stats/most-viewed"+query, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("most-viewed 期望 200，得到 %d（%s）", resp.StatusCode, query)
	}
	var items []gen.MostViewedItem
	if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
		t.Fatalf("解析 most-viewed 失败: %v", err)
	}
	return items
}

// TestStatsMostViewedViews views 榜：窗口内 open 次数倒序 + 窗口边界 +
// limit 钳制 + 孤儿事件（已删资产）不入榜。
func TestStatsMostViewedViews(t *testing.T) {
	env := newTestEnv(t)
	a, b, c := testFiles[0], testFiles[1], testFiles[2]

	// 窗口内（今天）：a=3 次、c=2 次
	for i, s := range []string{"mv-s1", "mv-s2", "mv-s3"} {
		postViewEvent(t, env, a.id, gen.Open, s, env.clock.Now().Add(-time.Duration(i)*time.Minute))
	}
	postViewEvent(t, env, c.id, gen.Open, "mv-s5", env.clock.Now())
	postViewEvent(t, env, c.id, gen.Open, "mv-s6", env.clock.Now().Add(-time.Minute))
	// 窗口外（10 天前）：b +1，range=7d 不计
	postViewEvent(t, env, b.id, gen.Open, "mv-s7", localDayShift(env, -10, 9, 0, 0))
	// 窗口边界：7d 窗口首日（今天-6 天）00:00:30 → 计入；前一日 23:59 → 不计
	postViewEvent(t, env, b.id, gen.Open, "mv-s8", localDayShift(env, -6, 0, 0, 30))
	postViewEvent(t, env, b.id, gen.Open, "mv-s9", localDayShift(env, -7, 23, 59, 0))
	// 孤儿事件：引用不存在资产的 open → 榜单不返回（内连接 assets）
	seedViewEvent(t, env, uuid.NewString(), "open", "mv-orphan", env.clock.Now(), 0)

	// 缺省（metric=views、range=month 24 月窗口收全部）：a=3、b=3、c=2。
	// a/b 同分 tie-break = assetId 字典序（UUID 随机，不依赖具体顺序），
	// 按名字汇总断言集合 + 用 7d 榜验证排序确定性。
	all := getMostViewed(t, env, "")
	if len(all) != 3 {
		t.Fatalf("缺省榜单期望 3 项，得到 %d（%v）", len(all), all)
	}
	byNameAll := map[string]int{}
	for _, it := range all {
		byNameAll[it.FileName] = it.Value
	}
	if byNameAll["a.jpg"] != 3 || byNameAll["b.jpg"] != 3 || byNameAll["c.mp4"] != 2 {
		t.Fatalf("缺省榜期望 a=3/b=3/c=2（24 月窗口收 -10d/-7d 事件），得到 %v", byNameAll)
	}
	if all[2].Value != 2 {
		t.Fatalf("c 计 2 次应垫底，得到 %v", all)
	}
	// 榜首项资产字段（a/b 同分，榜首可能是其一——按名字取对应项断言）
	var first gen.MostViewedItem
	for _, it := range all {
		if it.FileName == "a.jpg" {
			first = it
		}
	}
	if first.AssetId.String() != a.id || first.MediaType != gen.MediaTypeImage {
		t.Fatalf("a.jpg 资产字段错误: %+v", first)
	}
	if first.ThumbUrl == nil || *first.ThumbUrl == "" {
		t.Fatalf("榜单项应带签名缩略图 URL: %+v", first)
	}

	// range=7d：窗口 = [今天-6 天 00:00, ...] → a=3、c=2、b=1
	//（b 的 -6d 00:00:30 计入；-7d 23:59 与 -10d 裁剪）
	b7 := getMostViewed(t, env, "?range=7d")
	byName := map[string]int{}
	for _, it := range b7 {
		byName[it.FileName] = it.Value
	}
	if byName["a.jpg"] != 3 || byName["c.mp4"] != 2 || byName["b.jpg"] != 1 {
		t.Fatalf("range=7d 期望 a=3/c=2/b=1（窗口首日含、前一日与 -10d 裁剪），得到 %v", byName)
	}

	// limit 钳制：limit=2 → 前 2 项；limit=0 → 回落缺省 20（不 400、返回全量）
	top2 := getMostViewed(t, env, "?limit=2")
	if len(top2) != 2 || top2[0].Value != 3 {
		t.Fatalf("limit=2 期望前 2 项，得到 %v", top2)
	}
	if got := getMostViewed(t, env, "?limit=0"); len(got) != 3 {
		t.Fatalf("limit=0 应回落缺省返回全量 3 项，得到 %d", len(got))
	}
}

// TestStatsMostViewedSeconds seconds 榜：dwell 累加倒序，无 dwell 的文件
// 不参与；open 计数不串味。
func TestStatsMostViewedSeconds(t *testing.T) {
	env := newTestEnv(t)
	a, b, c := testFiles[0], testFiles[1], testFiles[2]
	postDwellEvent(t, env, a.id, "dw-1", env.clock.Now(), 100)
	postDwellEvent(t, env, a.id, "dw-2", env.clock.Now(), 30)
	postDwellEvent(t, env, b.id, "dw-3", env.clock.Now(), 60)
	// c 只 open——不进 seconds 榜
	postViewEvent(t, env, c.id, gen.Open, "dw-s1", env.clock.Now())
	// 窗口外 dwell（-100 天）：a 再 +999，range=7d 不计；NULL seconds 的
	// dwell 行按 0 计（COALESCE），b 保持 60
	seedViewEvent(t, env, a.id, "dwell", "dw-old", localDayShift(env, -100, 8, 0, 0), 999)

	items := getMostViewed(t, env, "?metric=seconds&range=7d")
	if len(items) != 2 {
		t.Fatalf("seconds 榜期望 2 项（c 不参与），得到 %d（%v）", len(items), items)
	}
	if items[0].FileName != "a.jpg" || items[0].Value != 130 {
		t.Fatalf("seconds 榜首应 a.jpg×130，得到 %s×%d", items[0].FileName, items[0].Value)
	}
	if items[1].FileName != "b.jpg" || items[1].Value != 60 {
		t.Fatalf("seconds 榜次席应 b.jpg×60，得到 %s×%d", items[1].FileName, items[1].Value)
	}
	// views 榜与 seconds 榜互不串味（同一端点 metric 切换）
	v := getMostViewed(t, env, "?metric=views")
	if len(v) != 1 || v[0].FileName != "c.mp4" {
		t.Fatalf("views 榜应只有 c.mp4，得到 %v", v)
	}
}

// TestStatsMostViewedBadRequest：非法 metric / range → 400。
func TestStatsMostViewedBadRequest(t *testing.T) {
	env := newTestEnv(t)
	for _, q := range []string{"?metric=likes", "?range=decade"} {
		resp := env.do(t, http.MethodGet, "/api/v1/stats/most-viewed"+q, "")
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("%s 期望 400，得到 %d", q, resp.StatusCode)
		}
	}
}

// TestStatsTopAuthors 常看作者：窗口内作者资产 open 次数倒序；COS 作者按
// 其 COS 资产计；窗口裁剪。
func TestStatsTopAuthors(t *testing.T) {
	env := newTestEnv(t)
	aReg := seedAuthorFixture(t, env, "画师A", "reg-1.jpg", false)
	seedAuthorFixture(t, env, "画师A", "reg-2.jpg", false) // 同名常规作者 → 同 ID，两资产
	c1 := seedAuthorFixture(t, env, "作者X", "作者X/作品P/1.jpg", true)

	postViewEvent(t, env, aReg, gen.Open, "ta-s1", env.clock.Now())
	postViewEvent(t, env, aReg, gen.Open, "ta-s2", env.clock.Now())
	postViewEvent(t, env, env.assetIDByPath(t, "reg-2.jpg"), gen.Open, "ta-s3", env.clock.Now()) // 画师A 合计 3
	postViewEvent(t, env, c1, gen.Open, "ta-s4", env.clock.Now())                                // 作者X = 1
	// 窗口外（10 天前）：画师A +5 → range=7d 不计
	postViewEvent(t, env, aReg, gen.Open, "ta-s5", localDayShift(env, -10, 9, 0, 0))

	fetch := func(query string) []gen.TopAuthorItem {
		t.Helper()
		resp := env.do(t, http.MethodGet, "/api/v1/stats/top-authors"+query, "")
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("top-authors 期望 200，得到 %d", resp.StatusCode)
		}
		var items []gen.TopAuthorItem
		if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
			t.Fatalf("解析 top-authors 失败: %v", err)
		}
		return items
	}
	all := fetch("")
	if len(all) != 2 {
		t.Fatalf("期望 2 位作者，得到 %d（%v）", len(all), all)
	}
	// 缺省 range=month（24 月窗口）收 -10 天事件 → 画师A 合计 4
	if all[0].DisplayName != "画师A" || all[0].Views != 4 {
		t.Fatalf("榜首应为 画师A×4，得到 %s×%d", all[0].DisplayName, all[0].Views)
	}
	if all[1].DisplayName != "作者X" || all[1].Views != 1 || all[1].AuthorId == "" {
		t.Fatalf("次席应为 作者X×1，得到 %s×%d（id=%s）", all[1].DisplayName, all[1].Views, all[1].AuthorId)
	}
	b7 := fetch("?range=7d")
	if len(b7) != 2 || b7[0].Views != 3 || b7[1].Views != 1 {
		t.Fatalf("range=7d 应裁剪窗外事件 [画师A×3 作者X×1]，得到 %v", b7)
	}
}

// TestStatsTopTags 常看标签：窗口内带标签资产的 open 次数倒序 + 窗口裁剪。
func TestStatsTopTags(t *testing.T) {
	env := newTestEnv(t)
	a, b, c := testFiles[0], testFiles[1], testFiles[2]
	idA := createTag(t, env, "甲标签")
	idB := createTag(t, env, "乙标签")
	bind := func(asset, ids string) {
		t.Helper()
		resp := env.do(t, http.MethodPut, "/api/v1/assets/"+asset+"/tags", `{"tagIds":[`+ids+`]}`)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusNoContent {
			t.Fatalf("绑定期望 204，得到 %d", resp.StatusCode)
		}
	}
	bind(a.id, `"`+idA+`"`)
	bind(b.id, `"`+idA+`"`)
	bind(c.id, `"`+idB+`"`)

	// 甲标签（a+b）：窗口内 2 次 + 窗外 1 次；乙标签（c）：3 次
	postViewEvent(t, env, a.id, gen.Open, "tt-s1", env.clock.Now())
	postViewEvent(t, env, b.id, gen.Open, "tt-s2", env.clock.Now())
	postViewEvent(t, env, a.id, gen.Open, "tt-s3", localDayShift(env, -10, 9, 0, 0))
	postViewEvent(t, env, c.id, gen.Open, "tt-s4", env.clock.Now())
	postViewEvent(t, env, c.id, gen.Open, "tt-s5", env.clock.Now())
	postViewEvent(t, env, c.id, gen.Open, "tt-s6", env.clock.Now())

	fetch := func(query string) []gen.TopTagItem {
		t.Helper()
		resp := env.do(t, http.MethodGet, "/api/v1/stats/top-tags"+query, "")
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("top-tags 期望 200，得到 %d", resp.StatusCode)
		}
		var items []gen.TopTagItem
		if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
			t.Fatalf("解析 top-tags 失败: %v", err)
		}
		return items
	}
	all := fetch("")
	if len(all) != 2 || all[0].Tag != "乙标签" || all[0].Views != 3 || all[1].Tag != "甲标签" || all[1].Views != 3 {
		t.Fatalf("缺省榜期望 [乙标签×3 甲标签×3]（同分 tag 名序），得到 %v", all)
	}
	b7 := fetch("?range=7d")
	if len(b7) != 2 || b7[0].Views != 3 || b7[1].Views != 2 {
		t.Fatalf("range=7d 期望 [乙标签×3 甲标签×2]（窗外裁剪），得到 %v", b7)
	}
}

// TestStatsOverviewSourceCountsAndAvg：来源库存（§6 分区判定）与
// avgViewsPerFile（分子分母同窗口同限定）。
func TestStatsOverviewSourceCountsAndAvg(t *testing.T) {
	env := newTestEnv(t)
	// 默认库 3 资产（无作者 → normal=3）+ 1 个 COS 资产 → cos=1、total=4
	c1 := seedAuthorFixture(t, env, "作者X", "作者X/作品P/1.jpg", true)
	a := env.assetIDByPath(t, "a.jpg")

	// 窗口内：a 2 次、c1 1 次 → avg=3/2=1.5
	postViewEvent(t, env, a, gen.Open, "ov2-s1", env.clock.Now())
	postViewEvent(t, env, a, gen.Open, "ov2-s2", env.clock.Now())
	postViewEvent(t, env, c1, gen.Open, "ov2-s3", env.clock.Now())
	// 窗口外：b 1 次（range=7d 不计入 avg，但计入 totalViews）
	postViewEvent(t, env, env.assetIDByPath(t, "b.jpg"), gen.Open, "ov2-s4", localDayShift(env, -10, 9, 0, 0))

	o := getOverview(t, env)
	if o.SourceNormalCount == nil || *o.SourceNormalCount != 3 {
		t.Fatalf("sourceNormalCount 期望 3，得到 %v", o.SourceNormalCount)
	}
	if o.SourceCosCount == nil || *o.SourceCosCount != 1 {
		t.Fatalf("sourceCosCount 期望 1，得到 %v", o.SourceCosCount)
	}
	if o.TotalFiles == nil || *o.TotalFiles != 4 {
		t.Fatalf("totalFiles 期望 4（normal+cos=total），得到 %v", o.TotalFiles)
	}
	// 缺省（all=不设窗口）收全部事件：分子=4、分母=a/b/c1 共 3 文件 → 4/3
	if o.AvgViewsPerFile == nil || math.Abs(*o.AvgViewsPerFile-4.0/3.0) > 1e-9 {
		t.Fatalf("缺省（all）avg 期望 4/3，得到 %v", o.AvgViewsPerFile)
	}
	if o.TotalViews == nil || *o.TotalViews != 4 {
		t.Fatalf("totalViews（与 range 无关）期望 4，得到 %v", o.TotalViews)
	}

	// range=7d：窗口内 3/2 仍 = 1.5（-10 天的 b 不计入分子分母）
	resp := env.do(t, http.MethodGet, "/api/v1/stats/overview?range=7d", "")
	var o7 gen.StatsOverview
	if err := json.NewDecoder(resp.Body).Decode(&o7); err != nil {
		t.Fatalf("解析 overview 失败: %v", err)
	}
	_ = resp.Body.Close()
	if o7.AvgViewsPerFile == nil || *o7.AvgViewsPerFile != 1.5 {
		t.Fatalf("range=7d avg 期望 1.5，得到 %v", o7.AvgViewsPerFile)
	}

	// 非法 range → 400
	resp = env.do(t, http.MethodGet, "/api/v1/stats/overview?range=decade", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("非法 range 期望 400，得到 %d", resp.StatusCode)
	}
}

// TestStatsOverviewAvgNull：无浏览事件 → avgViewsPerFile = null（非 0）。
func TestStatsOverviewAvgNull(t *testing.T) {
	env := newTestEnv(t)
	o := getOverview(t, env)
	if o.AvgViewsPerFile != nil {
		t.Fatalf("无浏览 avg 期望 null，得到 %v", *o.AvgViewsPerFile)
	}
	if o.SourceCosCount == nil || *o.SourceCosCount != 0 || o.SourceNormalCount == nil || *o.SourceNormalCount != 3 {
		t.Fatalf("默认库来源库存期望 normal=3 cos=0，得到 %v/%v", o.SourceNormalCount, o.SourceCosCount)
	}
}

// TestStatsTrendsSourceFilter：trends source 参数按 §6 来源桶过滤物化行。
func TestStatsTrendsSourceFilter(t *testing.T) {
	env := newTestEnv(t)
	a := env.assetIDByPath(t, "a.jpg")                            // 常规桶（无 COS 关联）
	c1 := seedAuthorFixture(t, env, "作者X", "作者X/作品P/1.jpg", true) // COS 桶
	today := store.FormatDay(todayLocal(env))
	seedDailyRow(t, env, a, today, 5, 0, 0)
	seedDailyRow(t, env, c1, today, 3, 0, 0)

	lastView := func(query string) int {
		t.Helper()
		buckets := getTrends(t, env, query)
		if len(buckets) == 0 {
			t.Fatalf("%s 期望非空桶", query)
		}
		last := buckets[len(buckets)-1]
		if last.ViewCount == nil {
			t.Fatalf("%s 末桶 viewCount 缺失", query)
		}
		return *last.ViewCount
	}
	if got := lastView("?range=day"); got != 8 {
		t.Fatalf("缺省（不过滤）期望 8，得到 %d", got)
	}
	if got := lastView("?range=day&source=cos"); got != 3 {
		t.Fatalf("source=cos 期望 3，得到 %d", got)
	}
	if got := lastView("?range=day&source=normal"); got != 5 {
		t.Fatalf("source=normal 期望 5，得到 %d", got)
	}
	// 非法 source → 400
	resp := env.do(t, http.MethodGet, "/api/v1/stats/trends?range=day&source=both", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("非法 source 期望 400，得到 %d", resp.StatusCode)
	}
}
