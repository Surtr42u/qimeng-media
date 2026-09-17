// auth_login_test.go：密码登录端点（/auth/login）测试。
// 覆盖：正确密码签发新会话 token 且旧 token 仍有效（多设备并发会话
// 模型，migration 0011）、错误密码 401、重复登录的多个 token 并存。
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

// TestLogin_correctPassword_oldTokenStaysValid：
// 正确密码 → 新 token 可用，setup 时的旧 token 仍有效（多会话并存，
// 旧"签发即重铸"语义已随 0011 废弃）。
func TestLogin_correctPassword_oldTokenStaysValid(t *testing.T) {
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
		t.Fatal("登录应签发新会话 token，不应复用旧 token")
	}
	if got := doWithToken(t, e, *tok.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("新 token 访问应 200，got %d", got)
	}
	if got := doWithToken(t, e, oldToken, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("多会话模型：登录不应吊销旧 token（应 200），got %d", got)
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

// TestLogin_reloginTokensCoexist：连续两次登录，两个 token 并存有效
// （多设备并发会话：手机/电脑各持一个 token 互不干扰）。
func TestLogin_reloginTokensCoexist(t *testing.T) {
	e := newTestEnv(t)
	_, first := loginBody(t, e, testPasswordMain)
	if got := doWithToken(t, e, *first.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Fatalf("第一次登录 token 应有效，got %d", got)
	}
	_, second := loginBody(t, e, testPasswordMain)
	if got := doWithToken(t, e, *second.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("第二次登录 token 应有效，got %d", got)
	}
	if got := doWithToken(t, e, *first.Token, http.MethodGet, "/api/v1/libraries"); got != http.StatusOK {
		t.Errorf("多会话模型：第二次登录后第一次的 token 应仍有效（200），got %d", got)
	}
}
