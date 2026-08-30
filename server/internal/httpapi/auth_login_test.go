// auth_login_test.go：密码登录端点（/auth/login）测试。
// 覆盖：正确密码换新 token 且旧 token 失效（单用户单 token 重铸语义）、
// 错误密码 401、重复登录各自拿到可用 token（每次重铸）。
package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// loginBody 调 /auth/login（免鉴权端点），返回状态码与 AuthToken。
func loginBody(t *testing.T, e *testEnv, password string) (int, *gen.AuthToken) {
	t.Helper()
	resp, err := http.Post(e.ts.URL+"/api/v1/auth/login", "application/json",
		strings.NewReader(`{"password":"`+password+`"}`))
	if err != nil {
		t.Fatalf("login 请求失败: %v", err)
	}
	defer resp.Body.Close()
	var raw map[string]any
	body, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(body, &raw)
	tokBytes, _ := json.Marshal(raw)
	var tok gen.AuthToken
	_ = json.Unmarshal(tokBytes, &tok)
	t.Logf("login resp: status=%d body=%s", resp.StatusCode, string(body))
	return resp.StatusCode, &tok
}

// doWithToken 用指定 token 调一个鉴权端点，返回状态码。
func doWithToken(t *testing.T, e *testEnv, token, method, path string) int {
	t.Helper()
	req, err := http.NewRequest(method, e.ts.URL+path, nil)
	if err != nil {
		t.Fatalf("构造请求失败: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer resp.Body.Close()
	return resp.StatusCode
}

// TestLogin_correctPassword_issuesNewTokenAndInvalidatesOld：
// 正确密码 → 新 token 可用、setup 时的旧 token 失效（重铸语义）。
func TestLogin_correctPassword_issuesNewTokenAndInvalidatesOld(t *testing.T) {
	e := newTestEnv(t)
	oldToken := e.token // newTestEnv 内 setupAndSeed 产生的初始 token

	code, tok := loginBody(t, e, testPasswordMain)
	if code != http.StatusOK {
		t.Fatalf("正确密码应 200，got %d", code)
	}
	if tok.Token == nil || *tok.Token == "" {
		t.Fatal("登录响应应携带新 token")
	}
	if *tok.Token == oldToken {
		t.Fatal("登录应重铸新 token，不应复用旧 token")
	}
	if got := doWithToken(t, e, *tok.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("新 token 访问应 200，got %d", got)
	}
	if got := doWithToken(t, e, oldToken, http.MethodGet, "/api/v1/libraries"); got != http.StatusUnauthorized {
		t.Errorf("旧 token 应被重铸失效（401），got %d", got)
	}
}

// TestLogin_wrongPassword_returns401：错误密码 401，不颁发 token。
func TestLogin_wrongPassword_returns401(t *testing.T) {
	e := newTestEnv(t)
	code, tok := loginBody(t, e, testPasswordMain+"wrong")
	if code != http.StatusUnauthorized {
		t.Fatalf("错误密码应 401，got %d", code)
	}
	if tok.Token != nil && *tok.Token != "" {
		t.Error("登录失败不应返回 token")
	}
}

// TestLogin_reloginEachIssuesWorkingToken：连续两次登录，各自的 token
// 在下一次登录前均有效（第二次登录重铸后才失效第一次的）。
func TestLogin_reloginEachIssuesWorkingToken(t *testing.T) {
	e := newTestEnv(t)
	_, first := loginBody(t, e, testPasswordMain)
	if got := doWithToken(t, e, *first.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Fatalf("第一次登录 token 应有效，got %d", got)
	}
	_, second := loginBody(t, e, testPasswordMain)
	if got := doWithToken(t, e, *second.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("第二次登录 token 应有效，got %d", got)
	}
	if got := doWithToken(t, e, *first.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusUnauthorized {
		t.Errorf("第二次登录后第一次的 token 应失效（401），got %d", got)
	}
}
