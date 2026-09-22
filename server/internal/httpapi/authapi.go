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

// maxSessionsPerUser 是每用户并发会话上限。
// 为什么是 16：真实设备数（手机/平板/电脑/电视）≈3，留一个数量级的
// 裕量（换浏览器、清缓存重登都不误伤）；超出裁最旧，被裁设备下次
// 请求 401 重登——上限的意义是防 dev-login 每次冷启动无限涨行。
const maxSessionsPerUser = 16

// deviceLabelMaxLen 是会话 device_label 的截断长度（登录 User-Agent 的
// 字符数上限）：UA 动辄数百字符，标签只需可辨认设备，按 rune 截断
// 避免切破多字节字符。
const deviceLabelMaxLen = 128

// authState 维护 M1 单用户鉴权状态：全部有效会话的 token 哈希集合。
//
// 设计要点：
//   - 多设备并发会话模型（migration 0011）：每次登录 INSERT 一条
//     auth_sessions，多端并存；logout 按哈希吊销单条——旧"单 token
//     覆盖重铸"模型在多端场景互踢，已废弃；
//   - 服务重启后会话仍有效——New 时从库加载哈希集合进内存（SECURITY
//     「鉴权设计」），每请求比对走内存，热路径零库查询的承诺不变；
//   - 明文 token 永不落库（auth_sessions.token_hash 与旧 users.token_hash
//     同为 SHA-256 hex），签发响应是明文 token 在世上存在的唯一一次；
//   - users 表按多用户设计但 M1 只写一行；CountUsers>0 即"已初始化"。
type authState struct {
	mu sync.RWMutex
	// hashes 是当前有效会话的 token 哈希集合；空 map 表示未初始化
	//（verify 恒 false）。M1 单用户：集合只含首行用户的会话。
	hashes map[string]struct{}
}

func newAuthState() *authState { return &authState{hashes: map[string]struct{}{}} }

// load 启动时从库加载首行用户的全部会话哈希；库中无用户（未 setup）
// 或无任何会话（全部已登出）都得到空集合，不报错。
func (a *authState) load(ctx context.Context, q *db.Queries) error {
	u, err := q.GetFirstUser(ctx)
	if errors.Is(err, sql.ErrNoRows) {
		return nil // 未初始化：首次 setup 走 POST /auth/setup
	}
	if err != nil {
		return err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.reloadLocked(ctx, q, u.ID)
}

// reloadLocked 以库内该用户的最新会话集合整体替换内存集合。
// 调用方必须已持有 a.mu 写锁（登录/登出路径在写锁内做 DB 写后调用，
// 保证内存与库的一致性不被并发请求打断）。
func (a *authState) reloadLocked(ctx context.Context, q *db.Queries, userID string) error {
	hashes, err := q.ListSessionHashesByUser(ctx, userID)
	if err != nil {
		return err
	}
	m := make(map[string]struct{}, len(hashes))
	for _, h := range hashes {
		m[h] = struct{}{}
	}
	a.hashes = m
	return nil
}

// removeHash 登出路径的内存同步：删掉被吊销会话的哈希（DB 行已删）。
func (a *authState) removeHash(tokenHash string) {
	a.mu.Lock()
	delete(a.hashes, tokenHash)
	a.mu.Unlock()
}

// verify 是 auth.RequireToken 的校验回调：请求 token 的哈希命中任一
// 有效会话即放行。
//
// 为什么用 map 成员查找而非逐条恒时比对：键是请求 token 的 SHA-256
// hex，攻击者不知道库中存储的哈希值（库里也只有哈希），无法构造目标
// 键去利用查找的时序差异——时序侧信道在此无实义；集合语义（多会话）
// 下 map 是最简正确实现。
func (a *authState) verify(_ context.Context, tokenHash string) bool {
	a.mu.RLock()
	defer a.mu.RUnlock()
	_, ok := a.hashes[tokenHash]
	return ok
}

// truncateDeviceLabel 把 User-Agent 截到 deviceLabelMaxLen 个字符。
// 空 UA 返回空串（列默认值同语义，显式传参保持单一路径）。
func truncateDeviceLabel(ua string) string {
	runes := []rune(ua)
	if len(runes) <= deviceLabelMaxLen {
		return ua
	}
	return string(runes[:deviceLabelMaxLen])
}

// registerSessionLocked 登记一条新会话并把该用户会话数压回上限。
// 调用方必须已持有 s.authState.mu 写锁：DB 写与内存集合替换在同一
// 锁临界区内完成，verify（RLock）看到的集合始终是库的一致快照。
//
// 裁剪语义：保留最新 maxSessionsPerUser 条（created_at DESC，id 决胜
// 毫秒同值），最旧的逐条删——每次登录最多超限 1 条，循环删除成本
// 可忽略；等价的单条 DELETE 子查询写法被 sqlc v1.31.1 SQLite 解析器
// 拒绝（见 sessions.sql 的 ListSessionIDsByUser 注释）。
func (s *Server) registerSessionLocked(r *http.Request, userID, tokenHash string) error {
	if err := s.q.CreateSession(r.Context(), db.CreateSessionParams{
		ID:          uuid.NewString(),
		UserID:      userID,
		TokenHash:   tokenHash,
		DeviceLabel: truncateDeviceLabel(r.UserAgent()),
		CreatedAt:   store.FormatTimestamp(s.now()),
	}); err != nil {
		return err
	}
	ids, err := s.q.ListSessionIDsByUser(r.Context(), userID)
	if err != nil {
		return err
	}
	if len(ids) > maxSessionsPerUser { // 未超限时零删除，不触发越界切片
		for _, id := range ids[maxSessionsPerUser:] {
			if err := s.q.DeleteSessionByID(r.Context(), id); err != nil {
				return err
			}
		}
	}
	// 以库内最新集合刷新内存：被裁掉的旧会话若仍留在内存会继续放行
	// 直到重启才收敛。
	return s.authState.reloadLocked(r.Context(), s.q, userID)
}

// PostApiV1AuthSetup 首次初始化：设置管理密码，返回一次性明文 token。
//
// 幂等保护：users 表已有行（CountUsers>0）一律 409——即使攻击者在
// 局域网内抢先访问，也只能拿到 409，抢不走已设置的账户。
func (s *Server) PostApiV1AuthSetup(w http.ResponseWriter, r *http.Request) {
	if !s.authLimit.allow(s.now()) {
		writeErr(w, http.StatusTooManyRequests, codeRateLimited, "尝试过于频繁，请稍后再试")
		return
	}
	var req gen.PostApiV1AuthSetupJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	// minLength: 8（openapi AuthSetupRequest 约定；绑定层不校验 body
	// schema，这里手动兜底）。
	if len(req.Password) < 8 {
		writeErr(w, http.StatusBadRequest, codeWeakPassword, "密码至少 8 位")
		return
	}

	// 双检锁的第一检（无锁快路径）：已初始化的系统直接 409，不为注定
	// 失败的请求付 argon2 哈希成本（免鉴权端点，防无谓 CPU 消耗面）。
	s.authState.mu.RLock()
	initialized := len(s.authState.hashes) != 0
	s.authState.mu.RUnlock()
	if initialized {
		writeErr(w, http.StatusConflict, codeAlreadySetup, "系统已初始化，禁止重复设置")
		return
	}
	// 慢操作（argon2id m=64MB，亚秒~秒级）在锁外预计算：曾放锁内，期间
	// 全部 Bearer 请求的 verify（RLock）被写锁挡住——首装时刻并发量低，
	// 但修复成本为零、语义不变（下方锁内仍有双保险复查）。
	token, err := auth.GenerateToken()
	if err != nil {
		s.internalErr(w, "auth setup: 生成 token", err)
		return
	}
	phc, err := auth.HashPassword(req.Password)
	if err != nil {
		s.internalErr(w, "auth setup: 哈希密码", err)
		return
	}

	s.authState.mu.Lock()
	defer s.authState.mu.Unlock()
	if len(s.authState.hashes) != 0 {
		writeErr(w, http.StatusConflict, codeAlreadySetup, "系统已初始化，禁止重复设置")
		return
	}
	// 双检锁的第二检（双保险）：内存态来自库，但为防多实例/外部写库的
	// 边角，落库前再查一次。
	n, err := s.q.CountUsers(r.Context())
	if err != nil {
		s.internalErr(w, "auth setup: 查询用户数", err)
		return
	}
	if n > 0 {
		writeErr(w, http.StatusConflict, codeAlreadySetup, "系统已初始化，禁止重复设置")
		return
	}

	userID := uuid.NewString()
	if err := s.q.CreateUser(r.Context(), db.CreateUserParams{
		ID:           userID,
		Name:         "admin", // M1 单用户固定名；多用户化时由输入决定
		PasswordHash: phc,
		Role:         "admin",
		// users.token_hash 自 0011 起弃用（NOT NULL 兼容照填首个哈希，
		// 鉴权只认 auth_sessions）——列删除需走 expand-migrate-contract。
		TokenHash: auth.TokenHash(token),
		CreatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "auth setup: 写入用户", err)
		return
	}
	if err := s.registerSessionLocked(r, userID, auth.TokenHash(token)); err != nil {
		s.internalErr(w, "auth setup: 登记会话", err)
		return
	}
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

// PostApiV1AuthLogin 密码登录：密码换新会话 token（多设备并发模型）。
//
// 每次登录 INSERT 一条新会话（device_label 记录登录 UA），已有设备的
// 会话不受影响——旧"签发即重铸"模型在多端场景互踢（手机登录 = 电脑
// 401），已废弃。未初始化（无用户行）返回 401：与 verify 的未初始化
// 行为一致，不泄露"系统是否已初始化"之外的信息。
func (s *Server) PostApiV1AuthLogin(w http.ResponseWriter, r *http.Request) { //nolint:revive // 生成接口要求的方法名
	if !s.authLimit.allow(s.now()) {
		writeErr(w, http.StatusTooManyRequests, codeRateLimited, "尝试过于频繁，请稍后再试")
		return
	}
	var req gen.PostApiV1AuthLoginJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	if req.Password == "" {
		writeErr(w, http.StatusUnauthorized, codeUnauthorized, "密码错误")
		return
	}
	u, err := s.q.GetFirstUser(r.Context())
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusUnauthorized, codeUnauthorized, "系统未初始化")
			return
		}
		s.internalErr(w, "auth login: 查询用户", err)
		return
	}
	ok, err := auth.VerifyPassword(req.Password, u.PasswordHash)
	if err != nil {
		// 哈希损坏是数据问题而非认证失败——不能伪装成 401 混过去
		s.internalErr(w, "auth login: 校验密码哈希", err)
		return
	}
	if !ok {
		writeErr(w, http.StatusUnauthorized, codeUnauthorized, "密码错误")
		return
	}
	token, err := auth.GenerateToken()
	if err != nil {
		s.internalErr(w, "auth login: 生成 token", err)
		return
	}
	s.authState.mu.Lock()
	defer s.authState.mu.Unlock()
	if err := s.registerSessionLocked(r, u.ID, auth.TokenHash(token)); err != nil {
		s.internalErr(w, "auth login: 登记会话", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.AuthToken{Token: &token})
}

// PostApiV1AuthDevLogin 开发模式免密登录：免密码直接签发会话 token。
//
// 安全边界（SECURITY.md「开发模式」节）：仅 config.AuthDevMode=true 时
// 生效，否则恒 404——生产环境该端点"不存在于语义层面"，前端据此回退
// 正常 setup/login 表单。与 /auth/login 同语义：每次调用签发一条新会话
// （dev 冷启动频繁，会话上限裁剪兜底防涨行）；未初始化时自动创建
// admin 占位用户，保证"免密码直奔 UI"的开发体验（用户约定：项目未
// 完成前不要密码流程）。
func (s *Server) PostApiV1AuthDevLogin(w http.ResponseWriter, r *http.Request) { //nolint:revive // 生成接口要求的方法名
	if !s.authLimit.allow(s.now()) {
		writeErr(w, http.StatusTooManyRequests, codeRateLimited, "尝试过于频繁，请稍后再试")
		return
	}
	if !s.cfg.AuthDevMode {
		writeErr(w, http.StatusNotFound, codeDevDisabled, "开发模式未开启")
		return
	}

	s.authState.mu.Lock()
	defer s.authState.mu.Unlock()
	// 未初始化：自动创建占位 admin（随机密码仅作哈希占位——dev 登录不经
	// 密码校验，明文无需可找回；行存在即可，与 setup 的 409 语义隔离）。
	if len(s.authState.hashes) == 0 {
		if err := s.ensureDevUser(r.Context()); err != nil {
			s.internalErr(w, "dev login: 自动初始化", err)
			return
		}
	}
	token, err := auth.GenerateToken()
	if err != nil {
		s.internalErr(w, "dev login: 生成 token", err)
		return
	}
	u, err := s.q.GetFirstUser(r.Context())
	if err != nil {
		s.internalErr(w, "dev login: 查询用户", err)
		return
	}
	if err := s.registerSessionLocked(r, u.ID, auth.TokenHash(token)); err != nil {
		s.internalErr(w, "dev login: 登记会话", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.AuthToken{Token: &token})
}

// PostApiV1AuthLogout 登出：吊销当前请求所用的那条会话（204）。
//
// 该端点不在免鉴权清单（server.go default 分支，走 RequireToken）：
// token 哈希已由中间件算好放进 Principal，这里按哈希精确删库行 +
// 内存集合同步。其他设备的会话不受影响（多会话模型的登出语义）。
// 重复登出同一 token 时 verify 已不命中，中间件直接 401。
func (s *Server) PostApiV1AuthLogout(w http.ResponseWriter, r *http.Request) {
	p, ok := auth.PrincipalFromContext(r.Context())
	if !ok {
		// 取不到 Principal = 路由漏挂鉴权，属程序错误而非客户端错误
		s.logger.Error("auth logout: 上下文缺少 Principal（路由漏挂鉴权？）")
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return
	}
	if err := s.q.DeleteSessionByTokenHash(r.Context(), p.TokenHash); err != nil {
		s.internalErr(w, "auth logout: 删除会话", err)
		return
	}
	s.authState.removeHash(p.TokenHash)
	w.WriteHeader(http.StatusNoContent)
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
		// 弃用列的占位（NOT NULL 兼容）：dev 链路不产生"首个会话哈希"，
		// 填随机值即可，鉴权只认 auth_sessions。
		TokenHash: auth.TokenHash(pass),
		CreatedAt: store.FormatTimestamp(s.now()),
	})
}
