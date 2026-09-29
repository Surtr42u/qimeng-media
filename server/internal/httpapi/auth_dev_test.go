// auth_dev_test.go：开发模式免密登录端点（/auth/dev-login）测试。
// 覆盖：dev 开关关闭时恒 404（生产零行为变化）、开启时未初始化自动建
// admin 并签发可用 token、二次调用再签一条会话（旧 token 仍有效，与
// login 的多会话语义一致）、可选共享密钥门禁三分支（对密钥 200 /
// 错密钥或缺头 401 / 未配置密钥时不带头仍 200）。
package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// devSecretHeader 是 dev-login 共享密钥的请求头名（api/openapi.yaml 的
// header 参数）；测试用它模拟客户端携带密钥。
const devSecretHeader = "X-Qimeng-Dev-Secret"

// testDevSharedSecret 是共享密钥门禁用例的测试密钥（测试文件内常量，
// 不散落字面量）。
const testDevSharedSecret = "test-dev-shared-secret-0123456789"

// devLogin 调 /auth/dev-login（免鉴权端点，不带头），返回状态码与 AuthToken。
func devLogin(t *testing.T, e *testEnv) (int, *gen.AuthToken) {
	t.Helper()
	return devLoginWithSecret(t, e, "")
}

// devLoginWithSecret 调 /auth/dev-login 并按需携带 X-Qimeng-Dev-Secret
// 请求头（secret 为空 = 不带头，覆盖未配置门禁的现状路径）。
func devLoginWithSecret(t *testing.T, e *testEnv, secret string) (int, *gen.AuthToken) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, e.ts.URL+"/api/v1/auth/dev-login", nil)
	if err != nil {
		t.Fatalf("构造 dev-login 请求失败: %v", err)
	}
	if secret != "" {
		req.Header.Set(devSecretHeader, secret)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("dev-login 请求失败: %v", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("读 dev-login 响应失败: %v", err)
	}
	var tok gen.AuthToken
	// 门禁失败时响应体是错误 JSON 而非 AuthToken：解析失败只记录不致命，
	// 状态码断言交给调用方。
	if err := json.Unmarshal(raw, &tok); err != nil {
		t.Logf("dev-login 响应非 AuthToken（status=%d）: %v", resp.StatusCode, err)
	}
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

// TestDevLogin_sharedSecret_matched：服务端配置了共享密钥且请求携带相等
// 密钥 → 正常签发 token（门禁放行路径）。
func TestDevLogin_sharedSecret_matched(t *testing.T) {
	e := newTestEnvRaw(t)
	e.cfg.AuthDevMode = true
	e.cfg.AuthDevSharedSecret = testDevSharedSecret

	code, tok := devLoginWithSecret(t, e, testDevSharedSecret)
	if code != http.StatusOK {
		t.Fatalf("携带正确密钥应 200，got %d", code)
	}
	if tok.Token == nil || *tok.Token == "" {
		t.Fatal("门禁放行后应返回 token")
	}
}

// TestDevLogin_sharedSecret_mismatchedOrMissing：密钥不匹配或不携带均 401，
// 且不签发 token——内嵌形态下同机其他 App 拿不到管理员会话。
func TestDevLogin_sharedSecret_mismatchedOrMissing(t *testing.T) {
	e := newTestEnvRaw(t)
	e.cfg.AuthDevMode = true
	e.cfg.AuthDevSharedSecret = testDevSharedSecret

	for name, secret := range map[string]string{"带错密钥": "wrong-secret", "不带头": ""} {
		t.Run(name, func(t *testing.T) {
			code, tok := devLoginWithSecret(t, e, secret)
			if code != http.StatusUnauthorized {
				t.Fatalf("密钥不匹配/缺失应 401，got %d", code)
			}
			if tok.Token != nil && *tok.Token != "" {
				t.Error("401 不应返回 token")
			}
		})
	}
}

// TestDevLogin_sharedSecretUnconfigured_noHeaderStill200：密钥未配置
// （cfg 空）时不带头仍 200——门禁默认关闭，行为与本特性引入前一致。
func TestDevLogin_sharedSecretUnconfigured_noHeaderStill200(t *testing.T) {
	e := newTestEnvRaw(t)
	e.cfg.AuthDevMode = true // AuthDevSharedSecret 保持零值空串

	code, tok := devLogin(t, e)
	if code != http.StatusOK {
		t.Fatalf("未配置密钥时不带头应 200，got %d", code)
	}
	if tok.Token == nil || *tok.Token == "" {
		t.Fatal("未配置门禁时应正常返回 token")
	}
}
