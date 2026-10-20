package httpapi

import (
	"net/url"
	"os"
	"slices"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
)

// 显式执行的 HTTP 全链探针：含鉴权、列表、批量字段、总数/日期桶和 JSON。
// 只建合成元数据，不生成/请求原件或缩略图；常规测试跳过，不设耗时断言。
func TestAssetsLargeLibraryPerformanceProbe(t *testing.T) {
	if os.Getenv("QIMENG_TEST_LARGE_LIBRARY") != "1" {
		t.Skip("需显式设置 QIMENG_TEST_LARGE_LIBRARY=1，使用隔离 5 万资产合成库")
	}
	env := newTestEnv(t)
	initial := fetchAssetPage(t, env, "?includeCos=true")
	expected := *initial.TotalMatched + 50000
	statements := []struct {
		query string
		args  []any
	}{
		{`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<50000) INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) SELECT printf('10000000-0000-4000-8000-%012d',x),?,x||'.jpg',x||'.jpg','image',1024,strftime('%Y-%m-%dT%H:%M:%fZ','2025-01-01','+'||x||' minutes'),'2026-01-01','2026-01-01' FROM n`, []any{env.libID}},
		{`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<250000) INSERT INTO view_events(asset_id,kind,session_id,started_at) SELECT printf('10000000-0000-4000-8000-%012d',x%50000+1),'open','s'||x,'2026-01-01T00:00:00.000Z' FROM n`, nil},
	}
	for _, statement := range statements {
		if _, err := env.conn.Exec(statement.query, statement.args...); err != nil {
			t.Fatal(err)
		}
	}
	base := "?includeCos=true&limit=60"
	first := fetchAssetPage(t, env, base)
	if first.NextCursor == nil {
		t.Fatal("首屏缺少游标")
	}
	for _, probe := range []struct {
		name, query string
		first       bool
	}{
		{"first_total", base, true},
		{"first_total_and_dates", base + "&dateCounts=true&tzOffsetMinutes=480", true},
		{"cursor", base + "&dateCounts=true&cursor=" + url.QueryEscape(*first.NextCursor), false},
	} {
		t.Run(probe.name, func(t *testing.T) {
			fetchAssetPage(t, env, probe.query) // 预热；下列仅报告稳态五次中位数。
			times := make([]time.Duration, 0, 5)
			var page gen.AssetPage
			for i := 0; i < 5; i++ {
				started := time.Now()
				page = fetchAssetPage(t, env, probe.query)
				times = append(times, time.Since(started))
			}
			if page.Items == nil || len(*page.Items) != 60 {
				t.Fatal("页大小应为60")
			}
			if probe.first {
				if page.TotalMatched == nil || *page.TotalMatched != expected {
					t.Fatal("首屏总数错误")
				}
			} else if page.TotalMatched != nil || page.DateCounts != nil {
				t.Fatal("翻页不应重复总数/分桶")
			}
			if page.DateCounts != nil {
				sum := 0
				for _, bucket := range *page.DateCounts {
					sum += bucket.FileCount
				}
				if sum != expected {
					t.Fatalf("分桶之和=%d total=%d", sum, expected)
				}
			}
			seen := map[string]bool{}
			for _, item := range *page.Items {
				if item.Id == nil || seen[item.Id.String()] {
					t.Fatal("ID 无效/重复")
				}
				seen[item.Id.String()] = true
			}
			slices.Sort(times)
			t.Logf("HTTP p50=%s，5 samples，%d assets + 250000 opens", times[len(times)/2], expected)
		})
	}
}
