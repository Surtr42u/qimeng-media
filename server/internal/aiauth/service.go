package aiauth

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

const (
	// DefaultCacheTTL 是内存客户端缓存的最大陈旧时间（默认 5s）：
	// 撤销/轮换在最多一个窗口内生效，避免每个 AI 请求都查库；新客户端
	// 由管理命令（异地进程）创建后同样在该窗口内可用。
	DefaultCacheTTL = 5 * time.Second
	// lastUsedTouchInterval 是 last_used_at 的写库节流：精确到分钟足够
	// 审计"某客户端最近是否在用"，不用每个请求都写一次 SQLite。
	lastUsedTouchInterval = time.Minute
	// finalizeTimeout 是 last_used/审计等 best-effort 写库的上限，避免
	// 请求路径被慢库拖住。
	finalizeTimeout = 3 * time.Second
)

// 审计字段上限（TEXT 列无长度约束，但审计是运营面数据，写入前统一截断，
// 避免异常请求头/超长路径无限膨胀；截断不影响业务响应）。
const (
	// MaxAuditPathRunes 是审计 path 上限。
	MaxAuditPathRunes = 300
	// MaxAuditActionRunes 是审计 action 上限。
	MaxAuditActionRunes = 120
	// MaxAuditRequestIDRunes 是审计 requestId 上限。
	MaxAuditRequestIDRunes = 100
	// MaxAuditMethodRunes 是审计 method 上限。
	MaxAuditMethodRunes = 10
)

// Options 组装凭据服务（依赖注入只在组合根/main 或隔离测试发生）。
type Options struct {
	Conn    *sql.DB
	Queries *db.Queries
	// Now 时钟注入（测试用固定时钟验证过期/缓存 TTL，不依赖真实 sleep）。
	Now func() time.Time
	// Logger 结构化日志；nil 用 slog.Default。
	Logger *slog.Logger
	// CacheTTL 内存缓存最大陈旧时间；<=0 用 DefaultCacheTTL。
	CacheTTL time.Duration
}

// AuditEntry 是一次 AI 客户端写操作审计。只记录请求元数据：不记录请求体/
// 响应体，避免把作者档案等业务内容复制进审计表。
type AuditEntry struct {
	ClientID   string
	Method     string
	Path       string
	Action     string
	StatusCode int
	RequestID  string
}

// Service 维护 AI 客户端身份的内存缓存与审计写入。
//
// 缓存模型：所有客户端行（含已撤销/已过期）加载进 byHash，鉴权时再判
// Enabled——撤销只需刷新缓存即可生效，不需要在缓存里做条件删除。缓存
// 超过 CacheTTL 后由下一次鉴权触发 Reload；Reload 失败按鉴权失败处理
// （fail-closed），不把陈旧缓存继续当作有效授权。
type Service struct {
	conn     *sql.DB
	q        *db.Queries
	now      func() time.Time
	logger   *slog.Logger
	cacheTTL time.Duration

	mu       sync.RWMutex
	byHash   map[string]Client
	loadedAt time.Time

	// reloadMu 串行化缓存刷新，避免并发请求同时打库。
	reloadMu sync.Mutex

	// lastUsedMu/lastUsed 是 last_used_at 的写库节流表（clientID -> 上次写库
	// 时刻）；进程内仅用于节流，不参与鉴权判断。
	lastUsedMu sync.Mutex
	lastUsed   map[string]time.Time
}

// New 组装凭据服务并完成一次初始加载。加载失败返回错误：调用方（main /
// httpapi.New）应拒绝带病启动——空缓存会让所有 AI token 变成 401，静默
// 降级比启动失败更难排查。
func New(opts Options) (*Service, error) {
	if opts.Conn == nil {
		return nil, errors.New("aiauth: Options.Conn 必填")
	}
	q := opts.Queries
	if q == nil {
		q = db.New(opts.Conn)
	}
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	cacheTTL := opts.CacheTTL
	if cacheTTL <= 0 {
		cacheTTL = DefaultCacheTTL
	}
	service := &Service{
		conn:     opts.Conn,
		q:        q,
		now:      now,
		logger:   logger,
		cacheTTL: cacheTTL,
		byHash:   map[string]Client{},
		lastUsed: map[string]time.Time{},
	}
	if err := service.Reload(context.Background()); err != nil {
		return nil, fmt.Errorf("aiauth: 初始加载 AI 客户端失败: %w", err)
	}
	return service, nil
}

// Reload 从库整体刷新内存客户端缓存。scope/时间字段非法的行会被跳过并
// 记错误日志（fail-closed：宁可不认这个客户端，也不把坏配置当授权）。
func (s *Service) Reload(ctx context.Context) error {
	s.reloadMu.Lock()
	defer s.reloadMu.Unlock()
	rows, err := s.q.ListAiClients(ctx)
	if err != nil {
		return fmt.Errorf("aiauth: 加载 AI 客户端列表: %w", err)
	}
	byHash := make(map[string]Client, len(rows))
	for i := range rows {
		client, clientErr := clientFromRow(rows[i])
		if clientErr != nil {
			s.logger.Error("AI 客户端配置非法，已忽略该客户端（fail-closed）",
				"clientId", rows[i].ID, "error", clientErr)
			continue
		}
		byHash[rows[i].TokenHash] = client
	}
	s.mu.Lock()
	s.byHash = byHash
	s.loadedAt = s.now()
	s.mu.Unlock()
	return nil
}

// Authenticate 校验明文 token 并返回有效客户端。token 只在本次调用内做
// SHA-256 哈希，明文不进入日志/缓存/审计。
func (s *Service) Authenticate(ctx context.Context, token string) (Client, bool) {
	if token == "" {
		return Client{}, false
	}
	if err := s.ensureFresh(ctx); err != nil {
		// DB 抖动时按鉴权失败处理（fail-closed），不让陈旧缓存继续放行。
		s.logger.Warn("刷新 AI 客户端缓存失败，本次鉴权按失败处理", "error", err)
		return Client{}, false
	}
	hash := auth.TokenHash(token)
	s.mu.RLock()
	client, ok := s.byHash[hash]
	s.mu.RUnlock()
	if !ok {
		return Client{}, false
	}
	now := s.now().UTC()
	if !client.Enabled(now) {
		return Client{}, false
	}
	s.touchLastUsed(client.ID, now)
	return client, true
}

// ensureFresh 在缓存超过 TTL 时刷新一次；并发请求由 Reload 的 reloadMu 串行化。
func (s *Service) ensureFresh(ctx context.Context) error {
	s.mu.RLock()
	loadedAt := s.loadedAt
	s.mu.RUnlock()
	if s.now().Sub(loadedAt) < s.cacheTTL {
		return nil
	}
	return s.Reload(ctx)
}

// touchLastUsed 节流更新 last_used_at：best-effort，失败只记日志，不影响
// 已通过的身份校验（最近使用时间是运营信息，不是授权依据）。
func (s *Service) touchLastUsed(clientID string, now time.Time) {
	s.lastUsedMu.Lock()
	last, seen := s.lastUsed[clientID]
	if seen && now.Sub(last) < lastUsedTouchInterval {
		s.lastUsedMu.Unlock()
		return
	}
	s.lastUsed[clientID] = now
	s.lastUsedMu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), finalizeTimeout)
	defer cancel()
	if err := s.q.TouchAiClientLastUsed(ctx, db.TouchAiClientLastUsedParams{
		LastUsedAt: sql.NullString{String: store.FormatTimestamp(now), Valid: true},
		ID:         clientID,
	}); err != nil {
		s.logger.Warn("更新 AI 客户端最近使用时间失败（不影响本次请求）", "clientId", clientID, "error", err)
	}
}

// RecordAudit 追加一条 AI 写操作审计。调用方负责在失败时记录错误日志并
// 继续业务响应（审计失败不阻断业务）；本方法不做长期重试。
func (s *Service) RecordAudit(ctx context.Context, entry AuditEntry) error {
	if entry.ClientID == "" {
		return errors.New("aiauth: 审计缺少 clientId")
	}
	writeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), finalizeTimeout)
	defer cancel()
	err := s.q.InsertAiAudit(writeCtx, db.InsertAiAuditParams{
		ClientID:   entry.ClientID,
		Method:     truncate(entry.Method, MaxAuditMethodRunes),
		Path:       truncate(entry.Path, MaxAuditPathRunes),
		Action:     truncate(entry.Action, MaxAuditActionRunes),
		StatusCode: int64(entry.StatusCode),
		RequestID:  truncate(entry.RequestID, MaxAuditRequestIDRunes),
		CreatedAt:  store.FormatTimestamp(s.now().UTC()),
	})
	if err != nil {
		return fmt.Errorf("aiauth: 写入 AI 审计失败: %w", err)
	}
	return nil
}

// truncate 按 rune 截断（审计字段是展示/检索用文本，截断安全）。
func truncate(value string, max int) string {
	runes := []rune(value)
	if len(runes) <= max {
		return value
	}
	return string(runes[:max])
}
