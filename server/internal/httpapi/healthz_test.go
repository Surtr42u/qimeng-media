package httpapi

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestHealthz 锁定探针契约：200 + {"status":"alive"}。
// Docker healthcheck 与监控按这个响应判断存活，格式变更属于接口变更。
func TestHealthz(t *testing.T) {
	rec := httptest.NewRecorder()
	Healthz(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Code != http.StatusOK {
		t.Errorf("状态码 = %d, 期望 %d", rec.Code, http.StatusOK)
	}
	if got := rec.Body.String(); got != `{"status":"alive"}` {
		t.Errorf("响应体 = %q, 期望 %q", got, `{"status":"alive"}`)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("Content-Type = %q, 期望 application/json", ct)
	}
}

// TestHealthzRouting 锁定探针路由契约（协议归位 /api/v1 后的完整链路）：
//  1. /api/v1/healthz、/api/v1/readyz 免鉴权——无 token 直达（topRouter 白名单，
//     与 openapi.yaml security: [] 同步）；
//  2. 根路径 /healthz、/readyz 运维探针别名回归——200 + 同 body，不入协议面
//     但 docker/k8s 编排依赖它（docker-compose healthcheck）。
func TestHealthzRouting(t *testing.T) {
	env := newTestEnvRaw(t)

	for _, c := range []struct {
		path, want string
	}{
		{"/api/v1/healthz", "alive"},
		{"/api/v1/readyz", "ready"},
		{"/healthz", "alive"},     // 根路径别名（运维探针，非协议面）
		{"/readyz", "ready"},      // 根路径别名（运维探针，非协议面）
		{"/api/v1/libraries", ""}, // 对照：同请求无 token 的业务端点是 401
	} {
		resp, err := http.Get(env.ts.URL + c.path)
		if err != nil {
			t.Fatalf("请求 %s 失败: %v", c.path, err)
		}
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		switch {
		case c.want == "":
			if resp.StatusCode != http.StatusUnauthorized {
				t.Errorf("%s（对照，无 token）期望 401，得到 %d", c.path, resp.StatusCode)
			}
		case resp.StatusCode != http.StatusOK || !strings.Contains(string(body), c.want):
			t.Errorf("%s 期望 200+%s（免鉴权直达），得到 %d+%s", c.path, c.want, resp.StatusCode, body)
		}
	}
}
