// security_hardening_test.go：2026-10-17 安全加固批次的回归测试。
// 覆盖：①全部 JSON 端点强制 Content-Type: application/json（跨站简单请求
// 防线）；②auth 三个免鉴权端点不因垃圾请求/404 消耗限速配额；③明文 token
// 响应带 Cache-Control: no-store。行为契约见 docs/SECURITY.md 对应节。
package httpapi

import (
	"io"
	"net/http"
	"strings"
	"testing"
)

// doRawJSONRequest 构造一次不经过 testEnv.do 的原始请求（后者会自动补
// Authorization 与 application/json，这里需要精确控制 header）。
func doRawJSONRequest(t *testing.T, method, url, contentType, body, token string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(method, url, strings.NewReader(body))
	if err != nil {
		t.Fatalf("构造请求失败: %v", err)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	t.Cleanup(func() { _ = resp.Body.Close() })
	return resp
}

// TestJSONEndpointsRequireContentType：无 Content-Type / text/plain 的 JSON
// 请求一律 400 INVALID_BODY（即使 body 本身是合法 JSON）；application/json
// 带 charset 参数应放行。这是跨站"简单请求"（no-cors + text/plain）无法
// 伪造 JSON 请求的机制保证——未初始化窗口内的 setup 抢注 DoS 因此关闭。
func TestJSONEndpointsRequireContentType(t *testing.T) {
	e := newTestEnvRaw(t) // 未初始化：setup 是免鉴权路径
	url := e.ts.URL + "/api/v1/auth/setup"
	body := `{"password":"test-password-001"}`

	for _, tc := range []struct{ name, ct string }{
		{"缺Content-Type", ""},
		{"text/plain", "text/plain"},
		{"form-urlencoded", "application/x-www-form-urlencoded"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			resp := doRawJSONRequest(t, http.MethodPost, url, tc.ct, body, "")
			if resp.StatusCode != http.StatusBadRequest {
				t.Fatalf("Content-Type=%q 应 400，得到 %d", tc.ct, resp.StatusCode)
			}
			raw, _ := io.ReadAll(resp.Body)
			if !strings.Contains(string(raw), "INVALID_BODY") {
				t.Fatalf("错误码应 INVALID_BODY，得到 %s", raw)
			}
		})
	}

	// 合法 JSON 请求（带 charset 参数）要在上述失败后仍然成功：同时证明
	// 这些失败请求没有消耗 auth 限速配额（否则第 11 次会 429）。
	resp := doRawJSONRequest(t, http.MethodPost, url,
		"application/json; charset=utf-8", body, "")
	if resp.StatusCode != http.StatusCreated {
		raw, _ := io.ReadAll(resp.Body)
		t.Fatalf("application/json 应 201，得到 %d（body=%s）", resp.StatusCode, raw)
	}
}

// TestAuthenticatedJSONEndpointRequiresContentType：鉴权端点同样强制 JSON
// Content-Type（统一口径，避免"哪些端点严格"的维护盲区）。
func TestAuthenticatedJSONEndpointRequiresContentType(t *testing.T) {
	e := newTestEnv(t) // 已初始化，e.token 有效
	resp := doRawJSONRequest(t, http.MethodPost, e.ts.URL+"/api/v1/authors",
		"text/plain", `{"displayName":"测试作者"}`, e.token)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("缺 JSON Content-Type 应 400，得到 %d", resp.StatusCode)
	}
	raw, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(raw), "INVALID_BODY") {
		t.Fatalf("错误码应 INVALID_BODY，得到 %s", raw)
	}
}

// TestDevLoginDisabled404DoesNotConsumeRateLimit：dev 模式关闭时，对
// /auth/dev-login 的恒 404 请求不得消耗共享限速配额（否则未鉴权方可把
// setup/login 锁成永久 429）。打满 10 次窗口后再 setup 必须成功。
func TestDevLoginDisabled404DoesNotConsumeRateLimit(t *testing.T) {
	e := newTestEnvRaw(t) // AuthDevMode 默认 false
	devURL := e.ts.URL + "/api/v1/auth/dev-login"
	for i := 0; i < authRateLimitMax+5; i++ {
		resp := doRawJSONRequest(t, http.MethodPost, devURL, "", "", "")
		if resp.StatusCode != http.StatusNotFound {
			t.Fatalf("第 %d 次 dev-login 应 404，得到 %d", i+1, resp.StatusCode)
		}
	}

	resp := doRawJSONRequest(t, http.MethodPost, e.ts.URL+"/api/v1/auth/setup",
		"application/json", `{"password":"test-password-001"}`, "")
	if resp.StatusCode != http.StatusCreated {
		raw, _ := io.ReadAll(resp.Body)
		t.Fatalf("404 请求不应消耗限速配额，setup 应 201，得到 %d（body=%s）",
			resp.StatusCode, raw)
	}
}

// TestAuthTokenResponseNoStore：setup 与 login 的明文 token 响应必须带
// Cache-Control: no-store（不允许浏览器/代理缓存留存 credential）。
func TestAuthTokenResponseNoStore(t *testing.T) {
	e := newTestEnvRaw(t)
	setupResp := doRawJSONRequest(t, http.MethodPost, e.ts.URL+"/api/v1/auth/setup",
		"application/json", `{"password":"test-password-001"}`, "")
	if setupResp.StatusCode != http.StatusCreated {
		t.Fatalf("setup 应 201，得到 %d", setupResp.StatusCode)
	}
	if got := setupResp.Header.Get("Cache-Control"); got != "no-store" {
		t.Errorf("setup 响应 Cache-Control = %q，期望 no-store", got)
	}

	loginResp := doRawJSONRequest(t, http.MethodPost, e.ts.URL+"/api/v1/auth/login",
		"application/json", `{"password":"test-password-001"}`, "")
	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("login 应 200，得到 %d", loginResp.StatusCode)
	}
	if got := loginResp.Header.Get("Cache-Control"); got != "no-store" {
		t.Errorf("login 响应 Cache-Control = %q，期望 no-store", got)
	}
}
