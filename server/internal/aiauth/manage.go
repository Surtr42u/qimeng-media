package aiauth

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

var (
	// ErrClientNotFound：AI 客户端 ID 不存在。
	ErrClientNotFound = errors.New("aiauth: AI 客户端不存在")
	// ErrClientRevoked：AI 客户端已撤销，不能轮换密钥。
	ErrClientRevoked = errors.New("aiauth: AI 客户端已撤销")
	// ErrInvalidInput：管理输入的标签/scope/设备/过期时间不合法。
	ErrInvalidInput = errors.New("aiauth: 参数不合法")
)

// CreateClientInput 是本地管理命令创建客户端的输入。
type CreateClientInput struct {
	Label     string
	Scopes    []Scope
	DeviceIDs []string
	// ExpiresAt 为空表示永不过期；非空必须晚于当前时间。
	ExpiresAt *time.Time
}

// CreateClient 创建 AI 客户端并返回一次性明文 token。明文只在这一刻存在：
// 库内只写 auth.TokenHash，内存缓存只按哈希索引。调用方（CLI）负责把
// token 输出到受控终端且绝不写日志/文件。
func (s *Service) CreateClient(ctx context.Context, in CreateClientInput) (Client, string, error) {
	label := strings.TrimSpace(in.Label)
	if label == "" || len([]rune(label)) > MaxLabelRunes {
		return Client{}, "", fmt.Errorf("%w: label 须为 1–%d 字符", ErrInvalidInput, MaxLabelRunes)
	}
	scopes, err := validateScopes(in.Scopes)
	if err != nil {
		return Client{}, "", err
	}
	deviceIDs, err := normalizeDeviceIDs(in.DeviceIDs)
	if err != nil {
		return Client{}, "", fmt.Errorf("%w: %s", ErrInvalidInput, err)
	}
	now := s.now().UTC()
	var expiresAt *time.Time
	if in.ExpiresAt != nil {
		value := in.ExpiresAt.UTC()
		if !value.After(now) {
			return Client{}, "", fmt.Errorf("%w: expiresAt 必须晚于当前时间", ErrInvalidInput)
		}
		expiresAt = &value
	}
	token, err := auth.GenerateToken()
	if err != nil {
		return Client{}, "", err
	}
	tokenHash := auth.TokenHash(token)
	scopesJSON, err := encodeStringArray(scopeStrings(scopes))
	if err != nil {
		return Client{}, "", err
	}
	deviceJSON, err := encodeStringArray(deviceIDs)
	if err != nil {
		return Client{}, "", err
	}
	id := uuid.NewString()
	err = s.q.CreateAiClient(ctx, db.CreateAiClientParams{
		ID:            id,
		Label:         label,
		TokenHash:     tokenHash,
		ScopesJson:    scopesJSON,
		DeviceIdsJson: deviceJSON,
		CreatedAt:     store.FormatTimestamp(now),
		ExpiresAt:     formatNullTimestamp(expiresAt),
		RevokedAt:     sql.NullString{},
		LastUsedAt:    sql.NullString{},
	})
	if err != nil {
		return Client{}, "", fmt.Errorf("aiauth: 创建 AI 客户端: %w", err)
	}
	client := Client{
		ID: id, Label: label, Scopes: scopes, DeviceIDs: deviceIDs,
		CreatedAt: now, ExpiresAt: expiresAt,
	}
	s.cacheClient(tokenHash, client)
	return client, token, nil
}

// ListClients 返回全部客户端（新→旧）。库内非法行返回错误而不是静默跳过：
// 管理面需要看到配置损坏，而不是"少了一个客户端"。
func (s *Service) ListClients(ctx context.Context) ([]Client, error) {
	rows, err := s.q.ListAiClients(ctx)
	if err != nil {
		return nil, fmt.Errorf("aiauth: 列出 AI 客户端: %w", err)
	}
	clients := make([]Client, 0, len(rows))
	for i := range rows {
		client, clientErr := clientFromRow(rows[i])
		if clientErr != nil {
			return nil, clientErr
		}
		clients = append(clients, client)
	}
	return clients, nil
}

// RevokeClient 撤销客户端。重复撤销幂等（已撤销直接返回 nil）：运维脚本
// 重跑不会因为"已经撤过"报错。
func (s *Service) RevokeClient(ctx context.Context, id string) error {
	id = strings.TrimSpace(id)
	if id == "" {
		return fmt.Errorf("%w: 客户端 ID 不能为空", ErrInvalidInput)
	}
	row, err := s.q.GetAiClient(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrClientNotFound
	}
	if err != nil {
		return fmt.Errorf("aiauth: 查询 AI 客户端: %w", err)
	}
	if row.RevokedAt.Valid {
		return nil
	}
	affected, err := s.q.RevokeAiClient(ctx, db.RevokeAiClientParams{
		RevokedAt: sql.NullString{String: store.FormatTimestamp(s.now().UTC()), Valid: true},
		ID:        id,
	})
	if err != nil {
		return fmt.Errorf("aiauth: 撤销 AI 客户端: %w", err)
	}
	if affected == 0 {
		return nil // 并发撤销已生效，语义等价
	}
	s.uncacheClient(row.TokenHash)
	return nil
}

// RotateClient 轮换客户端密钥：旧 token 立即失效，新明文 token 只返回一次。
// 已撤销客户端拒绝轮换（需要先重新创建，避免"撤销后被轮换复活"的语义漏洞）。
func (s *Service) RotateClient(ctx context.Context, id string) (Client, string, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return Client{}, "", fmt.Errorf("%w: 客户端 ID 不能为空", ErrInvalidInput)
	}
	row, err := s.q.GetAiClient(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return Client{}, "", ErrClientNotFound
	}
	if err != nil {
		return Client{}, "", fmt.Errorf("aiauth: 查询 AI 客户端: %w", err)
	}
	if row.RevokedAt.Valid {
		return Client{}, "", ErrClientRevoked
	}
	token, err := auth.GenerateToken()
	if err != nil {
		return Client{}, "", err
	}
	tokenHash := auth.TokenHash(token)
	affected, err := s.q.RotateAiClientToken(ctx, db.RotateAiClientTokenParams{
		TokenHash: tokenHash,
		ID:        id,
	})
	if err != nil {
		return Client{}, "", fmt.Errorf("aiauth: 轮换 AI 客户端密钥: %w", err)
	}
	if affected == 0 {
		return Client{}, "", ErrClientRevoked
	}
	client, err := clientFromRow(row)
	if err != nil {
		return Client{}, "", err
	}
	// 缓存同时撤旧哈希、挂新哈希：旧 token 在本进程立即失效。
	s.uncacheClient(row.TokenHash)
	s.cacheClient(tokenHash, client)
	return client, token, nil
}

// cacheClient/uncacheClient 是缓存维护口（create/rotate/revoke 同进程写路径
// 立即生效；异地 CLI 写的生效窗口 = CacheTTL，见 DefaultCacheTTL 注释）。
func (s *Service) cacheClient(tokenHash string, client Client) {
	s.mu.Lock()
	s.byHash[tokenHash] = client
	s.mu.Unlock()
}

func (s *Service) uncacheClient(tokenHash string) {
	s.mu.Lock()
	delete(s.byHash, tokenHash)
	s.mu.Unlock()
}

// validateScopes 校验 scope 列表（白名单 + 去重 + 非空）。
func validateScopes(scopes []Scope) ([]Scope, error) {
	raw := make([]string, 0, len(scopes))
	for _, scope := range scopes {
		raw = append(raw, string(scope))
	}
	parsed, err := ParseScopes(raw)
	if err != nil {
		return nil, fmt.Errorf("%w: %s", ErrInvalidInput, err)
	}
	return parsed, nil
}

// scopeStrings 把 scope 列表转成库内 JSON 字符串列表。
func scopeStrings(scopes []Scope) []string {
	out := make([]string, 0, len(scopes))
	for _, scope := range scopes {
		out = append(out, string(scope))
	}
	return out
}
