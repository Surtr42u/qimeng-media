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

// PostApiV1AuthLogin 密码登录：密码换新 token（换设备/清缓存找回访问权）。
//
// 单用户单 token 模型：登录成功即重铸 token（新哈希覆盖旧哈希），旧
// token 随之失效——同机换浏览器后旧浏览器需重新登录，换来的是"token
// / 丢失时总有密码这条找回路径"（openapi /auth/login description 语义）。
// 未初始化（无用户行）返回 401：与 verify 的未初始化行为一致，不泄露
// "系统是否已初始化"之外的信息。
func (s *Server) PostApiV1AuthLogin(w http.ResponseWriter, r *http.Request) { //nolint:revive // 生成接口要求的方法名
	var req gen.PostApiV1AuthLoginJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	if req.Password == "" {
		writeErr(w, http.StatusUnauthorized, "UNAUTHORIZED", "密码错误")
		return
	}
	u, err := s.q.GetFirstUser(r.Context())
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusUnauthorized, "UNAUTHORIZED", "系统未初始化")
			return
		}
		s.logger.Error("auth login: 查询用户失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	ok, err := auth.VerifyPassword(req.Password, u.PasswordHash)
	if err != nil {
		// 哈希损坏是数据问题而非认证失败——不能伪装成 401 混过去
		s.logger.Error("auth login: 校验密码哈希失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	if !ok {
		writeErr(w, http.StatusUnauthorized, "UNAUTHORIZED", "密码错误")
		return
	}
	token, err := auth.GenerateToken()
	if err != nil {
		s.logger.Error("auth login: 生成 token 失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	if err := s.q.UpdateUserTokenHash(r.Context(), auth.TokenHash(token)); err != nil {
		s.logger.Error("auth login: 更新 token 失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	s.authState.mu.Lock()
	s.authState.tokenHash = auth.TokenHash(token)
	s.authState.mu.Unlock()
	writeJSON(w, http.StatusOK, gen.AuthToken{Token: &token})
}

// PostApiV1AuthDevLogin 开发模式免密登录：免密码直接签发 token。
//
// 安全边界（SECURITY.md「开发模式」节）：仅 config.AuthDevMode=true 时
// 生效，否则恒 404——生产环境该端点"不存在于语义层面"，前端据此回退
// 正常 setup/login 表单。与 /auth/login 同语义：签发即重铸，旧 token
// 失效；未初始化时自动创建 admin 占位用户，保证"免密码直奔 UI"的
// 开发体验（用户约定：项目未完成前不要密码流程）。
func (s *Server) PostApiV1AuthDevLogin(w http.ResponseWriter, r *http.Request) { //nolint:revive // 生成接口要求的方法名
	if !s.cfg.AuthDevMode {
		writeErr(w, http.StatusNotFound, "DEV_DISABLED", "开发模式未开启")
		return
	}

	s.authState.mu.Lock()
	defer s.authState.mu.Unlock()
	// 未初始化：自动创建占位 admin（随机密码仅作哈希占位——dev 登录不经
	// 密码校验，明文无需可找回；行存在即可，与 setup 的 409 语义隔离）。
	if s.authState.tokenHash == "" {
		if err := s.ensureDevUser(r.Context()); err != nil {
			s.logger.Error("dev login: 自动初始化失败", "err", err)
			writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
			return
		}
	}
	token, err := auth.GenerateToken()
	if err != nil {
		s.logger.Error("dev login: 生成 token 失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	if err := s.q.UpdateUserTokenHash(r.Context(), auth.TokenHash(token)); err != nil {
		s.logger.Error("dev login: 更新 token 失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	s.authState.tokenHash = auth.TokenHash(token)
	writeJSON(w, http.StatusOK, gen.AuthToken{Token: &token})
}

// ensureDevUser 在 dev 模式未初始化时创建占位 admin 用户。
// 随机密码（GenerateToken 的 64 hex，> 8 位）仅作哈希占位。
func (s *Server) ensureDevUser(ctx context.Context) error {
	n, err := s.q.CountUsers(ctx)
	if err != nil {
		return err
	}
	if n > 0 {
		return nil // 官方链路（setup/login）已建用户，无需占位
	}
	pass, err := auth.GenerateToken()
	if err != nil {
		return err
	}
	phc, err := auth.HashPassword(pass)
	if err != nil {
		return err
	}
	return s.q.CreateUser(ctx, db.CreateUserParams{
		ID:           uuid.NewString(),
		Name:         "admin",
		PasswordHash: phc,
		Role:         "admin",
		TokenHash:    auth.TokenHash(pass),
		CreatedAt:    store.FormatTimestamp(s.now()),
	})
}
