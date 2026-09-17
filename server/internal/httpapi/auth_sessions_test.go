// auth_sessions_test.go：多设备并发会话模型（migration 0011）测试。
// 覆盖：setup 即会话、多端 token 并存、logout 单吊销、会话上限裁最旧、
// auth 限速 429、旧模型 token 经 0011 回填语句升级后仍有效。
package httpapi

import (
	"context"
	"net/http"
	"testing"
	"time"
)

// legacyBackfillSQL 与 migrations/0011_auth_sessions.up.sql 的回填语句
// 逐字保持一致（升级语义被本测试锁定，两侧改动必须同步）。
const legacyBackfillSQL = `INSERT INTO auth_sessions (id, user_id, token_hash, device_label, created_at)
SELECT 'legacy-single-token', id, token_hash, 'legacy', created_at FROM users;`

// TestSetup_issuesSessionToken：setup 签发的 token 即一条有效会话
// （verify 204 + auth_sessions 恰好一行）。
func TestSetup_issuesSessionToken(t *testing.T) {
	e := newTestEnv(t)
	if got := doWithToken(t, e, e.token, http.MethodPost, "/api/v1/auth/verify"); got != http.StatusNoContent {
		t.Fatalf("setup token verify 应 204，got %d", got)
	}
	u, err := e.q.GetFirstUser(t.Context())
	if err != nil {
		t.Fatalf("查询用户失败: %v", err)
	}
	if n, err := e.q.CountSessionsByUser(t.Context(), u.ID); err != nil || n != 1 {
		t.Errorf("setup 后应恰好 1 条会话（n=%d, err=%v）", n, err)
	}
}

// TestSessions_concurrentLogins_bothTokensValid：两台"设备"各自登录，
// 两个 token 同时可用（token 挤兑修复的核心语义）。
func TestSessions_concurrentLogins_bothTokensValid(t *testing.T) {
	e := newTestEnv(t)
	_, second := loginBody(t, e, testPasswordMain)
	if second.Token == nil || *second.Token == "" {
		t.Fatal("第二次登录应返回 token")
	}
	for name, token := range map[string]string{"设备A(setup)": e.token, "设备B(login)": *second.Token} {
		if got := doWithToken(t, e, token, http.MethodPost, "/api/v1/auth/verify"); got != http.StatusNoContent {
			t.Errorf("%s 的 token 应同时有效（204），got %d", name, got)
		}
	}
}

// TestLogout_revokesOnlyCurrentSession：logout 只吊销当前请求的会话——
// A 登出后 A 401、B 仍 204；对已吊销 token 重复 logout 走中间件 401。
func TestLogout_revokesOnlyCurrentSession(t *testing.T) {
	e := newTestEnv(t)
	tokenA := e.token
	_, second := loginBody(t, e, testPasswordMain)
	tokenB := *second.Token

	if got := doWithToken(t, e, tokenA, http.MethodPost, "/api/v1/auth/logout"); got != http.StatusNoContent {
		t.Fatalf("logout 应 204，got %d", got)
	}
	if got := doWithToken(t, e, tokenA, http.MethodPost, "/api/v1/auth/verify"); got != http.StatusUnauthorized {
		t.Errorf("登出后的 tokenA 应 401，got %d", got)
	}
	if got := doWithToken(t, e, tokenB, http.MethodPost, "/api/v1/auth/verify"); got != http.StatusNoContent {
		t.Errorf("tokenB 不应被连带吊销（204），got %d", got)
	}
	// 重复 logout：tokenA 已不在有效集合，中间件直接 401（到不了 handler）
	if got := doWithToken(t, e, tokenA, http.MethodPost, "/api/v1/auth/logout"); got != http.StatusUnauthorized {
		t.Errorf("重复 logout 已吊销 token 应 401，got %d", got)
	}
}

// TestDevLogin_sessionCap_prunesOldest：连续 17 次 dev-login（超过上限
// 16），最旧的会话被裁（第 1 个 token 401），其余 16 个仍有效且库里
// 恰好 16 行。每次登录前推进 fakeClock 一分钟：既避开限速窗口，又让
// created_at 严格递增（裁剪顺序确定）。
func TestDevLogin_sessionCap_prunesOldest(t *testing.T) {
	e := newTestEnvRaw(t)
	e.cfg.AuthDevMode = true

	const total = maxSessionsPerUser + 1
	tokens := make([]string, 0, total)
	for i := 0; i < total; i++ {
		e.clock.advance(time.Minute)
		code, tok := devLogin(t, e)
		if code != http.StatusOK || tok.Token == nil {
			t.Fatalf("第 %d 次 dev-login 应 200 且带 token（code=%d）", i+1, code)
		}
		tokens = append(tokens, *tok.Token)
	}
	if got := doWithToken(t, e, tokens[0], http.MethodPost, "/api/v1/auth/verify"); got != http.StatusUnauthorized {
		t.Errorf("最旧会话应被裁（401），got %d", got)
	}
	for i, token := range tokens[1:] {
		if got := doWithToken(t, e, token, http.MethodPost, "/api/v1/auth/verify"); got != http.StatusNoContent {
			t.Errorf("第 %d 个 token 应仍有效（204），got %d", i+2, got)
		}
	}
	u, err := e.q.GetFirstUser(t.Context())
	if err != nil {
		t.Fatalf("查询用户失败: %v", err)
	}
	if n, err := e.q.CountSessionsByUser(t.Context(), u.ID); err != nil || n != maxSessionsPerUser {
		t.Errorf("会话数应被压回上限 %d（n=%d, err=%v）", maxSessionsPerUser, n, err)
	}
}

// TestAuthRateLimit_returns429AfterLimit：同一限速窗口内连续 11 次错误
// 密码 login，前 10 次 401、第 11 次 429；/auth/verify 不在限速范围
// （限速只挂 setup/login/dev-login 三个入口）。
func TestAuthRateLimit_returns429AfterLimit(t *testing.T) {
	e := newTestEnv(t)
	// setup 已消耗本窗口 1 次额度，推进时钟进入全新窗口让计数从 0 开始
	e.clock.advance(2 * time.Minute)

	for i := 1; i <= authRateLimitMax; i++ {
		if code, _ := loginBody(t, e, testPasswordMain+"wrong"); code != http.StatusUnauthorized {
			t.Fatalf("第 %d 次错误密码应 401（未到限速阈值），got %d", i, code)
		}
	}
	if code, _ := loginBody(t, e, testPasswordMain+"wrong"); code != http.StatusTooManyRequests {
		t.Fatalf("第 %d 次尝试应 429，got %d", authRateLimitMax+1, code)
	}
	// 正确密码也被限速拦（防借助限速差异做用户名/密码枚举推断）
	if code, _ := loginBody(t, e, testPasswordMain); code != http.StatusTooManyRequests {
		t.Fatalf("限速中的正确密码也应 429，got %d", code)
	}
	// 窗口过期后恢复放行
	e.clock.advance(authRateLimitWindow)
	if code, tok := loginBody(t, e, testPasswordMain); code != http.StatusOK || tok.Token == nil {
		t.Fatalf("窗口过期后登录应恢复 200，got %d", code)
	}
	// verify 不受限速影响（既有的有效会话不受 auth 入口限速牵连）
	if got := doWithToken(t, e, e.token, http.MethodPost, "/api/v1/auth/verify"); got != http.StatusNoContent {
		t.Errorf("verify 不应受限速影响（204），got %d", got)
	}
}

// TestSessions_legacyBackfill_revivesOldModelToken：模拟"升级前的库"
// （users.token_hash 有效、auth_sessions 清空），重放 0011 的回填语句后
// 重启鉴权状态（load），旧模型 token 作为 legacy 会话继续有效——
// 锁定 migration 回填与 load 读 sessions 表的正确性。
func TestSessions_legacyBackfill_revivesOldModelToken(t *testing.T) {
	e := newTestEnv(t)
	// users.token_hash 即 setup 时的哈希（0011 后弃用不再更新），清空
	// 会话表 + 清空内存集合（模拟"重启后 load 之前"的状态）即回到旧模型形态
	if _, err := e.conn.ExecContext(t.Context(), "DELETE FROM auth_sessions"); err != nil {
		t.Fatalf("清空会话表失败: %v", err)
	}
	e.s.authState.mu.Lock()
	e.s.authState.hashes = map[string]struct{}{}
	e.s.authState.mu.Unlock()
	if got := doWithToken(t, e, e.token, http.MethodPost, "/api/v1/auth/verify"); got != http.StatusUnauthorized {
		t.Fatalf("会话清空后 token 应失效（401），got %d", got)
	}
	if _, err := e.conn.ExecContext(t.Context(), legacyBackfillSQL); err != nil {
		t.Fatalf("执行回填语句失败: %v", err)
	}
	// 模拟服务重启：authState 从库重新加载
	if err := e.s.authState.load(context.Background(), e.q); err != nil {
		t.Fatalf("重载鉴权状态失败: %v", err)
	}
	if got := doWithToken(t, e, e.token, http.MethodPost, "/api/v1/auth/verify"); got != http.StatusNoContent {
		t.Errorf("回填后旧模型 token 应作为 legacy 会话复活（204），got %d", got)
	}
}
