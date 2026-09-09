package httpapi

// 推荐偏好端点（M3）测试：GET 无记录回显设计默认值（0.22/.../0.30）；
// PUT 自定义后 GET 回读一致（存储→JSON→回读往返）。

import (
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// getPrefs 读取推荐偏好。
func getPrefs(t *testing.T, env *testEnv) gen.RecommendPrefs {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/recommendations/prefs", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("读取偏好期望 200，得到 %d", resp.StatusCode)
	}
	var prefs gen.RecommendPrefs
	if err := json.NewDecoder(resp.Body).Decode(&prefs); err != nil {
		t.Fatalf("解析偏好失败: %v", err)
	}
	return prefs
}

// TestRecommendationPrefsDefault：无记录时返回设计默认值
// （DOMAIN_RULES §1.3 均衡推荐，字段指针全部填充）。
func TestRecommendationPrefsDefault(t *testing.T) {
	env := newTestEnv(t)
	p := getPrefs(t, env)
	checks := map[string]float64{
		"tagRelevance":  0.22,
		"tagCollection": 0.15,
		"engagement":    0.10,
		"recency":       0.15,
		"likeScore":     0.05,
		"discovery":     0.20,
		"freshness":     0.05,
		"browseDepth":   0.03,
		"maxRandom":     0.30,
	}
	if p.TagRelevance == nil || p.TagCollection == nil || p.Engagement == nil ||
		p.Recency == nil || p.LikeScore == nil || p.Discovery == nil ||
		p.Freshness == nil || p.BrowseDepth == nil || p.MaxRandom == nil {
		t.Fatalf("默认偏好九字段必须全部非空：%+v", p)
	}
	// 逐字段断言全部 9 个默认值（含 maxRandom，DOMAIN_RULES §1.3 均衡推荐）。
	got := map[string]float64{
		"tagRelevance":  *p.TagRelevance,
		"tagCollection": *p.TagCollection,
		"engagement":    *p.Engagement,
		"recency":       *p.Recency,
		"likeScore":     *p.LikeScore,
		"discovery":     *p.Discovery,
		"freshness":     *p.Freshness,
		"browseDepth":   *p.BrowseDepth,
		"maxRandom":     *p.MaxRandom,
	}
	for k, want := range checks {
		if got[k] != want {
			t.Errorf("字段 %s 默认值不符：期望 %v 得到 %v", k, want, got[k])
		}
	}
}

// TestRecommendationPrefsPutRoundtrip：PUT 自定义 → GET 回读一致。
func TestRecommendationPrefsPutRoundtrip(t *testing.T) {
	env := newTestEnv(t)
	body := `{"tagRelevance":0.5,"tagCollection":0.2,"engagement":0.11,` +
		`"recency":0.12,"likeScore":0.03,"discovery":0.21,"freshness":0.04,` +
		`"browseDepth":0.02,"maxRandom":0.25}`
	resp := env.do(t, http.MethodPut, "/api/v1/recommendations/prefs", body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("PUT 偏好期望 204，得到 %d", resp.StatusCode)
	}
	p := getPrefs(t, env)
	checks := map[string]float64{
		"tagRelevance":  0.5,
		"tagCollection": 0.2,
		"engagement":    0.11,
		"recency":       0.12,
		"likeScore":     0.03,
		"discovery":     0.21,
		"freshness":     0.04,
		"browseDepth":   0.02,
		"maxRandom":     0.25,
	}
	got := map[string]float64{
		"tagRelevance": *p.TagRelevance, "tagCollection": *p.TagCollection,
		"engagement": *p.Engagement, "recency": *p.Recency, "likeScore": *p.LikeScore,
		"discovery": *p.Discovery, "freshness": *p.Freshness,
		"browseDepth": *p.BrowseDepth, "maxRandom": *p.MaxRandom,
	}
	for k, want := range checks {
		if got[k] != want {
			t.Errorf("字段 %s 回读不一致：期望 %v 得到 %v", k, want, got[k])
		}
	}
}
