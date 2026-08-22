package httpapi

import (
	"net/http"
	"net/http/httptest"
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
