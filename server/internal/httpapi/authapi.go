package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"sync"

	"github.com/google/uuid"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// authState 维护 M1 单用户鉴权状态：users 表首行的 token 哈希缓存。
//
// 设计要点：
//   - 服务重启后 token 仍有效——New 时从库加载哈希进内存（SECURITY
//     「鉴权设计」），每请求比对走内存，不给热路径加库查询；
//   - 明文 token 永不落库（库里只有 TokenHash 的 SHA-256），setup
//     响应是明文 token 在世上存在的唯一一次；
//   - users 表按多用户设计但 M1 只写一行；CountUsers>0 即"已初始化"。
type authState struct {
	mu        sync.RWMutex
	tokenHash string // 空串表示未初始化（verify 恒 false）
}

func newAuthState() *authState { return &authState{} }

// load 启动时从库加载首行 token 哈希；库中无用户（未 setup）不报错。
func (a *authState) load(ctx context.Context, q *db.Queries) error {
	u, err := q.GetFirstUser(ctx)
	if errors.Is(err, sql.ErrNoRows) {
		return nil // 未初始化：首次 setup 走 POST /auth/setup
	}
	if err != nil {
		return err
	}
	a.mu.Lock()
	a.tokenHash = u.TokenHash
	a.mu.Unlock()
	return nil
}

// verify 是 auth.RequireToken 的校验回调：恒时比对请求 token 哈希
// 与库中存储哈希（auth.VerifyTokenHash 内部走 crypto/subtle）。
func (a *authState) verify(_ context.Context, tokenHash string) bool {
	a.mu.RLock()
	defer a.mu.RUnlock()
	if a.tokenHash == "" {
		return false // 未初始化：任何 token 都不放行
	}
	return auth.VerifyTokenHash(tokenHash, a.tokenHash)
}

// PostApiV1AuthSetup 首次初始化：设置管理密码，返回一次性明文 token。
//
// 幂等保护：users 表已有行（CountUsers>0）一律 409——即使攻击者在
// 局域网内抢先访问，也只能拿到 409，抢不走已设置的账户。
func (s *Server) PostApiV1AuthSetup(w http.ResponseWriter, r *http.Request) {
	var req gen.PostApiV1AuthSetupJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	// minLength: 8（openapi AuthSetupRequest 约定；绑定层不校验 body
	// schema，这里手动兜底）。
	if len(req.Password) < 8 {
		writeErr(w, http.StatusBadRequest, "WEAK_PASSWORD", "密码至少 8 位")
		return
	}

	s.authState.mu.Lock()
	defer s.authState.mu.Unlock()
	if s.authState.tokenHash != "" {
		writeErr(w, http.StatusConflict, "ALREADY_SETUP", "系统已初始化，禁止重复设置")
		return
	}
	// 双保险：内存态来自库，但为防多实例/外部写库的边角，落库前再查一次。
	n, err := s.q.CountUsers(r.Context())
	if err != nil {
		s.logger.Error("auth setup: 查询用户数失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	if n > 0 {
		writeErr(w, http.StatusConflict, "ALREADY_SETUP", "系统已初始化，禁止重复设置")
		return
	}

	token, err := auth.GenerateToken()
	if err != nil {
		s.logger.Error("auth setup: 生成 token 失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	phc, err := auth.HashPassword(req.Password)
	if err != nil {
		s.logger.Error("auth setup: 哈希密码失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	if err := s.q.CreateUser(r.Context(), db.CreateUserParams{
		ID:           uuid.NewString(),
		Name:         "admin", // M1 单用户固定名；多用户化时由输入决定
		PasswordHash: phc,
		Role:         "admin",
		TokenHash:    auth.TokenHash(token),
		CreatedAt:    store.FormatTimestamp(s.now()),
	}); err != nil {
		s.logger.Error("auth setup: 写入用户失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	s.authState.tokenHash = auth.TokenHash(token)
	// token 仅此一次明文返回（openapi AuthToken 语义）。
	writeJSON(w, http.StatusCreated, gen.AuthToken{Token: &token})
}

// PostApiV1AuthVerify 校验 token 有效性。
//
// 该端点位于鉴权中间件之后：能走到这里就证明 Bearer token 有效，
// 直接 204——响应语义即"鉴权链对当前 token 的裁决"。
func (s *Server) PostApiV1AuthVerify(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusNoContent)
}
