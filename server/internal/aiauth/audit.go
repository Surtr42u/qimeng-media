package aiauth

import (
	"context"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 审计查询限制。与 OpenAPI 的 GET /api/v1/ai-audit 参数一致：limit 1–200，
// 缺省 50；过滤串长度上限沿用写库时的截断口径，避免用超长串探测。
const (
	AuditDefaultLimit        = 50
	AuditMaxLimit            = 200
	MaxAuditClientIDRunes    = 100
	MaxAuditActionFilterRune = 120
	// auditCursorSeparator 是游标载荷内部分隔符（createdAt + 0x00 + id）。
	auditCursorSeparator = "\x00"
)

// ErrInvalidCursor 表示审计分页游标不合法（HTTP 层映射 400 INVALID_CURSOR）。
// 与 maintenance/deviceapply 的游标口径一致：游标是不透明串，客户端不得
// 自行拼接；解码失败一律拒绝而不是回退到第一页（避免悄然重复结果）。
var ErrInvalidCursor = errors.New("aiauth: 审计游标不合法")

// AuditFilter 是审计查询条件。空串表示不过滤。
type AuditFilter struct {
	ClientID     string
	ActionPrefix string
	Limit        int
	Cursor       string
}

// AuditRecord 是一条 AI 写操作审计的公开视图。
type AuditRecord struct {
	ID         int64
	ClientID   string
	Method     string
	Path       string
	Action     string
	StatusCode int
	RequestID  string
	CreatedAt  time.Time
}

// AuditPage 是审计查询的一页；NextCursor 为空表示已到底。
type AuditPage struct {
	Items      []AuditRecord
	NextCursor string
}

// GetClient 按 ID 读取单个客户端（管理端点撤销后的响应装配用）。不存在
// 返回 ErrClientNotFound；非法配置行返回解析错误而不是静默跳过。
func (s *Service) GetClient(ctx context.Context, id string) (Client, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return Client{}, fmt.Errorf("%w: 客户端 ID 不能为空", ErrInvalidInput)
	}
	row, err := s.q.GetAiClient(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return Client{}, ErrClientNotFound
	}
	if err != nil {
		return Client{}, fmt.Errorf("aiauth: 查询 AI 客户端: %w", err)
	}
	return clientFromRow(row)
}

// ListAudit 返回 AI 写操作审计的一页（createdAt 倒序、id 倒序 tie-break）。
// 只读元数据，不返回请求/响应正文；审计行不因客户端撤销而隐藏。
func (s *Service) ListAudit(ctx context.Context, filter AuditFilter) (AuditPage, error) {
	clientID := strings.TrimSpace(filter.ClientID)
	if len([]rune(clientID)) > MaxAuditClientIDRunes {
		return AuditPage{}, fmt.Errorf("%w: clientId 过滤串过长", ErrInvalidInput)
	}
	actionPrefix := strings.TrimSpace(filter.ActionPrefix)
	if len([]rune(actionPrefix)) > MaxAuditActionFilterRune {
		return AuditPage{}, fmt.Errorf("%w: action 过滤串过长", ErrInvalidInput)
	}
	limit := filter.Limit
	if limit <= 0 {
		limit = AuditDefaultLimit
	}
	if limit > AuditMaxLimit {
		limit = AuditMaxLimit
	}
	beforeCreatedAt := ""
	var beforeID int64
	if cursor := strings.TrimSpace(filter.Cursor); cursor != "" {
		createdAt, id, err := decodeAuditCursor(cursor)
		if err != nil {
			return AuditPage{}, err
		}
		beforeCreatedAt, beforeID = createdAt, id
	}
	rows, err := s.q.ListAiAudit(ctx, db.ListAiAuditParams{
		ClientID:        clientID,
		ActionPrefix:    actionPrefix,
		BeforeCreatedAt: beforeCreatedAt,
		BeforeID:        beforeID,
		PageLimit:       int64(limit + 1),
	})
	if err != nil {
		return AuditPage{}, fmt.Errorf("aiauth: 查询 AI 审计: %w", err)
	}
	hasMore := len(rows) > limit
	if hasMore {
		rows = rows[:limit]
	}
	items := make([]AuditRecord, 0, len(rows))
	for _, row := range rows {
		createdAt, parseErr := time.Parse(store.TimestampLayout, row.CreatedAt)
		if parseErr != nil {
			return AuditPage{}, fmt.Errorf("aiauth: 审计时间戳不合法: %w", parseErr)
		}
		items = append(items, AuditRecord{
			ID:         row.ID,
			ClientID:   row.ClientID,
			Method:     row.Method,
			Path:       row.Path,
			Action:     row.Action,
			StatusCode: int(row.StatusCode),
			RequestID:  row.RequestID,
			CreatedAt:  createdAt,
		})
	}
	page := AuditPage{Items: items}
	if hasMore && len(items) > 0 {
		last := items[len(items)-1]
		page.NextCursor = encodeAuditCursor(store.FormatTimestamp(last.CreatedAt), last.ID)
	}
	return page, nil
}

func encodeAuditCursor(createdAt string, id int64) string {
	payload := createdAt + auditCursorSeparator + strconv.FormatInt(id, 10)
	return base64.RawURLEncoding.EncodeToString([]byte(payload))
}

func decodeAuditCursor(cursor string) (string, int64, error) {
	raw, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return "", 0, ErrInvalidCursor
	}
	parts := strings.SplitN(string(raw), auditCursorSeparator, 2)
	if len(parts) != 2 {
		return "", 0, ErrInvalidCursor
	}
	if _, err := time.Parse(store.TimestampLayout, parts[0]); err != nil {
		return "", 0, ErrInvalidCursor
	}
	id, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil || id <= 0 {
		return "", 0, ErrInvalidCursor
	}
	return parts[0], id, nil
}

// rateBucket 是单个客户端的令牌桶（per-minute 上限的进程内实现）。
type rateBucket struct {
	tokens    float64
	last      time.Time
	lastTouch time.Time
}

// rateLimiter 是 AI 客户端请求限流器（单进程）。多实例部署时每个实例各自
// 计数属于已知边界：本服务当前是单机/NAS 单实例形态，引入共享限流需要
// 外部存储，超出本阶段范围。桶按客户端 ID 隔离，客户端被撤销后桶自然过期。
type rateLimiter struct {
	mu         sync.Mutex
	perMinute  int
	burst      int
	buckets    map[string]*rateBucket
	callsSince int
	now        func() time.Time
}

const (
	// rateBucketIdleTTL 是空闲桶的清理门槛；rateBucketSweepEvery 控制扫描
	// 频率（每 N 次检查扫一遍），避免每请求全表遍历。
	rateBucketIdleTTL    = 10 * time.Minute
	rateBucketSweepEvery = 256
)

func newRateLimiter(perMinute, burst int, now func() time.Time) *rateLimiter {
	if perMinute <= 0 {
		return nil
	}
	if burst <= 0 {
		burst = perMinute / 5
		if burst < 10 {
			burst = 10
		}
	}
	return &rateLimiter{
		perMinute: perMinute,
		burst:     burst,
		buckets:   map[string]*rateBucket{},
		now:       now,
	}
}

// allow 扣减一个令牌。返回 ok=false 时 retryAfter 是补满一个令牌的保守
// 等待时长（供 Retry-After 头使用）。
func (l *rateLimiter) allow(clientID string) (time.Duration, bool) {
	if l == nil {
		return 0, true
	}
	now := l.now().UTC()
	l.mu.Lock()
	defer l.mu.Unlock()
	l.callsSince++
	if l.callsSince >= rateBucketSweepEvery {
		l.callsSince = 0
		for id, bucket := range l.buckets {
			if now.Sub(bucket.lastTouch) > rateBucketIdleTTL {
				delete(l.buckets, id)
			}
		}
	}
	bucket, ok := l.buckets[clientID]
	if !ok {
		bucket = &rateBucket{tokens: float64(l.burst), last: now}
		l.buckets[clientID] = bucket
	}
	ratePerSecond := float64(l.perMinute) / 60
	elapsed := now.Sub(bucket.last).Seconds()
	if elapsed > 0 {
		bucket.tokens += elapsed * ratePerSecond
		if bucket.tokens > float64(l.burst) {
			bucket.tokens = float64(l.burst)
		}
		bucket.last = now
	}
	bucket.lastTouch = now
	if bucket.tokens >= 1 {
		bucket.tokens--
		return 0, true
	}
	missing := 1 - bucket.tokens
	wait := time.Duration(missing / ratePerSecond * float64(time.Second))
	if wait < time.Second {
		wait = time.Second
	}
	return wait, false
}
