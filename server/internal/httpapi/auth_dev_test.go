// auth_dev_test.go：开发模式免密登录端点（/auth/dev-login）测试。
// 覆盖：dev 开关关闭时恒 404（生产零行为变化）、开启时未初始化自动建
// admin 并签发可用 token、二次调用再签一条会话（旧 token 仍有效，与
// login 的多会话语义一致）。
package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// devLogin 调 /auth/dev-login（免鉴权端点），返回状态码与 AuthToken。
func devLogin(t *testing.T, e *testEnv) (int, *gen.AuthToken) {
	t.Helper()
	resp, err := http.Post(e.ts.URL+"/api/v1/auth/dev-login", "application/json", nil)
	if err != nil {
		t.Fatalf("dev-login 请求失败: %v", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var tok gen.AuthToken
	_ = json.Unmarshal(raw, &tok)
	t.Logf("dev-login resp: status=%d body=%s", resp.StatusCode, string(raw))
	return resp.StatusCode, &tok
}

// TestDevLogin_disabled_returns404：dev 开关必须默认关闭（生产零变化）——
// 未开启时恒 404，不泄露任何信息。
func TestDevLogin_disabled_returns404(t *testing.T) {
	e := newTestEnvRaw(t)
	code, tok := devLogin(t, e)
	if code != http.StatusNotFound {
		t.Fatalf("dev 关闭应 404，got %d", code)
	}
	if tok.Token != nil && *tok.Token != "" {
		t.Error("404 不应返回 token")
	}
}

// TestDevLogin_enabled_bootstrapsAndIssuesWorkingToken：dev 开启 + 库未
// 初始化 → 自动创建 admin 占位用户并签发可用 token（直接可用于鉴权请求）。
func TestDevLogin_enabled_bootstrapsAndIssuesWorkingToken(t *testing.T) {
	e := newTestEnvRaw(t)
	e.cfg.AuthDevMode = true // 指针共享：与 Server 持同一 config 实例

	code, tok := devLogin(t, e)
	if code != http.StatusOK {
		t.Fatalf("dev 开启应 200，got %d", code)
	}
	if tok.Token == nil || *tok.Token == "" {
		t.Fatal("dev-login 应返回 token")
	}
	if got := doWithToken(t, e, *tok.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("dev token 访问应 200，got %d", got)
	}
	// 自动创建的用户不应为空：二次 dev-login 走重铸而非重建
	if n, err := e.q.CountUsers(t.Context()); err != nil || n != 1 {
		t.Errorf("dev 自动初始化应恰好 1 个用户（n=%d, err=%v）", n, err)
	}
}

// TestDevLogin_secondCallBothTokensValid：二次调用再签一条会话，
// 第一次的 token 仍有效（与 /auth/login 的多会话语义一致）。
func TestDevLogin_secondCallBothTokensValid(t *testing.T) {
	e := newTestEnvRaw(t)
	e.cfg.AuthDevMode = true

	_, first := devLogin(t, e)
	if got := doWithToken(t, e, *first.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Fatalf("第一次 dev token 应有效，got %d", got)
	}
	_, second := devLogin(t, e)
	if *first.Token == *second.Token {
		t.Fatal("dev-login 应签发新会话 token，不应复用旧 token")
	}
	if got := doWithToken(t, e, *second.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("第二次 dev token 应有效，got %d", got)
	}
	if got := doWithToken(t, e, *first.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("多会话模型：第二次签发后第一次的 token 应仍有效（200），got %d", got)
	}
}
