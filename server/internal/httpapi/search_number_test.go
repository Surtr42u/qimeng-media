package httpapi

import (
	"context"
	"net/url"
	"sort"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 2026-11-07 用户反馈「Android 搜索『朱诺 11』出现大量不相关内容」的 HTTP
// 端到端回归：隔离实例 + 合成资产，验证 q 的数字编号语义、列表/计数/分面
// 同口径，以及非数字关键词不回归。语料与 store 层 search_semantics_test.go
// 对齐（1/10/11/12/110、组图页 11 (1)/(2)/(4)、2011 年份、1080p 分辨率、
// png/mp4 混合媒体、非规范名「朱诺11」）。
var searchNumberFiles = []struct {
	name      string
	mediaType string
}{
	{"朱诺 1.png", "image"},
	{"朱诺 10.png", "image"},
	{"朱诺 11.png", "image"},
	{"朱诺 12.png", "image"},
	{"朱诺 110.png", "image"},
	{"朱诺 11 (1).png", "image"},
	{"朱诺 11 (2).png", "image"},
	{"朱诺 11 (4).png", "image"},
	{"朱诺 11（2）.png", "image"},
	{"朱诺 2011.png", "image"},
	{"朱诺 1080p.png", "image"},
	{"朱诺 11.mp4", "video"},
	{"朱诺11.png", "image"},
}

func seedSearchNumberData(t *testing.T, env *testEnv) {
	t.Helper()
	ctx := context.Background()
	now := store.FormatTimestamp(time.Now())
	for i, f := range searchNumberFiles {
		if _, err := env.q.UpsertAsset(ctx, db.UpsertAssetParams{
			AssetID: "num-" + string(rune('a'+i)), LibraryID: env.libID,
			RelPath: f.name, FileName: f.name, MediaType: f.mediaType,
			SizeBytes: int64(2048 + i), Mtime: now, CreatedAt: now, UpdatedAt: now,
		}); err != nil {
			t.Fatalf("插入合成资产 %q 失败: %v", f.name, err)
		}
	}
}

func searchAssetPage(t *testing.T, env *testEnv, rawQuery string) gen.AssetPage {
	t.Helper()
	resp := env.do(t, "GET", "/api/v1/assets?"+rawQuery, "")
	defer closeBody(resp)
	if resp.StatusCode != 200 {
		t.Fatalf("搜索请求 %q 期望 200，得到 %d", rawQuery, resp.StatusCode)
	}
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析搜索响应失败: %v", err)
	}
	return page
}

func searchPageNames(page gen.AssetPage) []string {
	names := []string{}
	if page.Items != nil {
		for _, item := range *page.Items {
			if item.FileName != nil {
				names = append(names, *item.FileName)
			}
		}
	}
	return names
}

func containsString(haystack []string, needle string) bool {
	for _, item := range haystack {
		if item == needle {
			return true
		}
	}
	return false
}

func sameStringSet(a, b []string) bool {
	left := append([]string(nil), a...)
	right := append([]string(nil), b...)
	sort.Strings(left)
	sort.Strings(right)
	return strings.Join(left, "\x00") == strings.Join(right, "\x00")
}

// assertNumberSearch 同时校验命中集合与 totalMatched（列表/计数同谓词）。
func assertNumberSearch(t *testing.T, env *testEnv, query string, want []string) {
	t.Helper()
	page := searchAssetPage(t, env, "q="+url.QueryEscape(query))
	if got := searchPageNames(page); !sameStringSet(got, want) {
		t.Errorf("q=%q 命中 %v，期望 %v", query, got, want)
	}
	if page.TotalMatched == nil || *page.TotalMatched != len(want) {
		t.Errorf("q=%q totalMatched=%v，期望 %d", query, page.TotalMatched, len(want))
	}
}

func TestSearchNumberTokenEndToEnd(t *testing.T) {
	env := newTestEnv(t)
	seedSearchNumberData(t, env)

	group11 := []string{
		"朱诺 11 (1).png", "朱诺 11 (2).png", "朱诺 11 (4).png",
		"朱诺 11.mp4", "朱诺 11.png", "朱诺 11（2）.png", "朱诺11.png",
	}
	cases := []struct {
		query string
		want  []string
	}{
		{"1", []string{"朱诺 1.png"}},
		{"10", []string{"朱诺 10.png"}},
		{"12", []string{"朱诺 12.png"}},
		{"110", []string{"朱诺 110.png"}},
		{"11", group11},
		// 裸数字不命中组图页码（(2) 是页码，不是独立编号）。
		{"2", nil},
		// 组图页 token 命中 ASCII/全角两种页面写法。
		{"(2)", []string{"朱诺 11 (2).png", "朱诺 11（2）.png"}},
		{"(4)", []string{"朱诺 11 (4).png"}},
		// 多词 AND：作者 + 编号；组图页组合。
		{"朱诺 11", group11},
		{"朱诺 1", []string{"朱诺 1.png"}},
		{"11 (1)", []string{"朱诺 11 (1).png"}},
	}
	for _, c := range cases {
		assertNumberSearch(t, env, c.query, c.want)
	}

	// 年份/分辨率：完整数字段命中，短 token 不误命中。
	assertNumberSearch(t, env, "2011", []string{"朱诺 2011.png"})
	assertNumberSearch(t, env, "1080", []string{"朱诺 1080p.png"})
	// 非数字 token 保持旧子串语义（含大小写折叠与混合字母数字 token）。
	assertNumberSearch(t, env, "1080p", []string{"朱诺 1080p.png"})
	assertNumberSearch(t, env, "1080P", []string{"朱诺 1080p.png"})
	assertNumberSearch(t, env, "mp4", []string{"c.mp4", "朱诺 11.mp4"})
	// 中文子串 + 数字编号 AND：只命中 1 号，10/11/110/2011 全部排除。
	assertNumberSearch(t, env, "朱诺 1", []string{"朱诺 1.png"})
	// 非数字语义不回归：作者子串命中全部合成语料。
	if page := searchAssetPage(t, env, "q="+url.QueryEscape("朱诺")); len(searchPageNames(page)) != len(searchNumberFiles) {
		t.Fatalf("q=朱诺 应命中 %d 条，得到 %v", len(searchNumberFiles), searchPageNames(page))
	}

	// 混合媒体 + 媒体类型筛选：11 号一组图 6 张、1 个视频。
	if page := searchAssetPage(t, env, "q=11&mediaType=video"); len(searchPageNames(page)) != 1 || searchPageNames(page)[0] != "朱诺 11.mp4" {
		t.Errorf("q=11&mediaType=video 应只命中视频，得到 %v", searchPageNames(page))
	}
	if page := searchAssetPage(t, env, "q=11&mediaType=image"); len(searchPageNames(page)) != 6 {
		t.Errorf("q=11&mediaType=image 应命中 6 张图，得到 %v", searchPageNames(page))
	}

	// 计数一致：dateCounts 的日桶合计 == totalMatched。
	page := searchAssetPage(t, env, "q=11&dateCounts=true&tzOffsetMinutes=480")
	if page.DateCounts == nil {
		t.Fatal("dateCounts=true 应返回日桶")
	}
	sum := 0
	for _, bucket := range *page.DateCounts {
		sum += bucket.FileCount
	}
	if page.TotalMatched == nil || sum != *page.TotalMatched {
		t.Errorf("q=11 日桶合计 %d != totalMatched %v", sum, page.TotalMatched)
	}

	// 分面同谓词：类型维的 all 桶 == totalMatched。
	resp := env.do(t, "GET", "/api/v1/assets/facets?q="+url.QueryEscape("11"), "")
	defer closeBody(resp)
	if resp.StatusCode != 200 {
		t.Fatalf("分面请求期望 200，得到 %d", resp.StatusCode)
	}
	var facets gen.AssetFacets
	if err := decodeBody(resp, &facets); err != nil {
		t.Fatalf("解析分面响应失败: %v", err)
	}
	allTypes := -1
	for _, bucket := range facets.Types {
		if bucket.Key == "all" {
			allTypes = bucket.FileCount
		}
	}
	if allTypes != len(group11) {
		t.Errorf("分面类型 all=%d，期望 %d（分面与列表分家）", allTypes, len(group11))
	}

	// 分页与计数一致：limit=2 翻页取回同一集合。
	first := searchAssetPage(t, env, "q=11&limit=2")
	if first.NextCursor == nil {
		t.Fatalf("q=11&limit=2 应有 nextCursor")
	}
	if first.TotalMatched == nil || *first.TotalMatched != len(group11) {
		t.Errorf("分页首屏 totalMatched=%v，期望 %d", first.TotalMatched, len(group11))
	}
	second := searchAssetPage(t, env, "q=11&limit=2&cursor="+url.QueryEscape(*first.NextCursor))
	union := append(searchPageNames(first), searchPageNames(second)...)
	// 只断言前两页共 4 条、互不重复且都属于 11 号集合；具体顺序由
	// fileDate desc + asset_id 决定（合成语料 mtime 相同，不锁顺序）。
	if len(union) != 4 {
		t.Fatalf("前两页应共 4 条，得到 %v", union)
	}
	seen := map[string]bool{}
	for _, name := range union {
		if seen[name] {
			t.Errorf("分页出现重复条目 %q", name)
		}
		seen[name] = true
		if !containsString(group11, name) {
			t.Errorf("分页返回不属于 11 号集合的条目 %q", name)
		}
	}
}
