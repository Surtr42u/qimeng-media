package httpapi

// 推荐流端点（M2 热度占位）测试：热度排序（viewCount 降序）、
// limit 生效、mediaType 过滤。占位语义锁定：M3 换实现时这些
// 用例是回归底线（排序基准可变，参数契约不可变）。

import (
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// recList 拉推荐流。
func recList(t *testing.T, env *testEnv, query string) []gen.AssetSummary {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/recommendations"+query, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("推荐流期望 200，得到 %d", resp.StatusCode)
	}
	var items []gen.AssetSummary
	if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
		t.Fatalf("解析推荐流失败: %v", err)
	}
	return items
}

func TestRecommendationsByHeat(t *testing.T) {
	env := newTestEnv(t)
	// 给 c.mp4 上报 3 次浏览（不同 sessionId），b.jpg 1 次，a.jpg 0 次
	for i, name := range []string{"c.mp4", "c.mp4", "c.mp4", "b.jpg"} {
		id, ok := env.assetIDByName(t, name)
		if !ok {
			t.Fatalf("测试前置失败：%s 不在列表", name)
		}
		resp := env.do(t, http.MethodPost, "/api/v1/events/view",
			`{"assetId":"`+id+`","kind":"open","startedAt":"2026-08-27T10:00:00Z","sessionId":"s`+string(rune('0'+i))+`"}`)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusAccepted {
			t.Fatalf("view 上报期望 202，得到 %d", resp.StatusCode)
		}
	}
	items := recList(t, env, "")
	if len(items) != 3 {
		t.Fatalf("推荐流期望 3 条，得到 %d", len(items))
	}
	// 热度降序：c.mp4(3) > b.jpg(1) > a.jpg(0)
	want := []string{"c.mp4", "b.jpg", "a.jpg"}
	for i, name := range want {
		if items[i].FileName == nil || *items[i].FileName != name {
			t.Errorf("推荐流第 %d 位 = %v, 期望 %s（热度降序）", i, items[i].FileName, name)
		}
	}
}

func TestRecommendationsLimitAndFilter(t *testing.T) {
	env := newTestEnv(t)
	if got := len(recList(t, env, "?limit=1")); got != 1 {
		t.Errorf("limit=1 期望 1 条，得到 %d", got)
	}
	videos := recList(t, env, "?mediaType=video")
	if len(videos) != 1 || videos[0].FileName == nil || *videos[0].FileName != "c.mp4" {
		t.Errorf("mediaType=video 期望只含 c.mp4，得到 %v", videos)
	}
}

// TestRecommendationsBadLimit：limit 越界 400（协议 maximum: 200）。
func TestRecommendationsBadLimit(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodGet, "/api/v1/recommendations?limit=500", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("limit=500 期望 400，得到 %d", resp.StatusCode)
	}
}
