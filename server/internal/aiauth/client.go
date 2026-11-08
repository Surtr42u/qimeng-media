package aiauth

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 输入上限（管理 CLI 与库行解析共用）。设备标签上限与
// deviceapply.MaxTargetDeviceIDRunes 同值口径（100），但两个包不做跨层
// 常量耦合：本包只约束"绑定表能安全存下的标签长度"。
const (
	// MaxLabelRunes 是客户端标签上限（管理可读名称）。
	MaxLabelRunes = 120
	// MaxDeviceIDRunes 是单个设备标签上限。
	MaxDeviceIDRunes = 100
	// MaxDeviceIDs 是单客户端可绑定的设备数上限。
	MaxDeviceIDs = 100
)

// Client 是已注册 AI 客户端的公开视图。不含 token 哈希：管理输出/日志
// 一律不打印凭据派生值，内存缓存内部按哈希索引。
type Client struct {
	ID         string
	Label      string
	Scopes     []Scope
	DeviceIDs  []string
	CreatedAt  time.Time
	ExpiresAt  *time.Time
	RevokedAt  *time.Time
	LastUsedAt *time.Time
}

// Enabled 报告客户端在 now 时刻是否仍可用：已撤销或已过期都视为不可用。
func (c Client) Enabled(now time.Time) bool {
	if c.RevokedAt != nil {
		return false
	}
	if c.ExpiresAt != nil && !now.Before(*c.ExpiresAt) {
		return false
	}
	return true
}

// HasScope 报告客户端是否被授予 scope（精确匹配；scope 不做通配/继承）。
func (c Client) HasScope(scope Scope) bool {
	for _, granted := range c.Scopes {
		if granted == scope {
			return true
		}
	}
	return false
}

// AllowsDevice 报告设备标签是否绑定到该客户端：device_ids 为空 = 不可用
// 设备端点（默认拒绝）；大小写敏感精确匹配（设备标签是运维填写的不透明
// 标识，不做宽容归一，避免 "Phone-A" 与 "phone-a" 被误认为同一授权）。
func (c Client) AllowsDevice(deviceID string) bool {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return false
	}
	for _, allowed := range c.DeviceIDs {
		if allowed == deviceID {
			return true
		}
	}
	return false
}

// Principal 是 AI 客户端身份的请求上下文视图（接入层 httpapi 注入/读取；
// 本包不依赖 httpapi，所以上下文键与访问器放在这里）。
type Principal struct {
	ClientID  string
	Label     string
	Scopes    []Scope
	DeviceIDs []string
}

// HasScope 报告请求主体是否持有 scope。
func (p Principal) HasScope(scope Scope) bool {
	for _, granted := range p.Scopes {
		if granted == scope {
			return true
		}
	}
	return false
}

// AllowsDevice 报告请求主体是否可访问该设备标签（语义同 Client.AllowsDevice）。
func (p Principal) AllowsDevice(deviceID string) bool {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return false
	}
	for _, allowed := range p.DeviceIDs {
		if allowed == deviceID {
			return true
		}
	}
	return false
}

// principalKey 是 ctx 存取 AI Principal 的键类型；独立未导出类型避免与
// 其他包的 ctx 值碰撞（与 auth.principalKey 同款纪律）。
type principalKey struct{}

// WithPrincipal 把 AI 客户端身份放进请求上下文。
func WithPrincipal(ctx context.Context, principal Principal) context.Context {
	return context.WithValue(ctx, principalKey{}, principal)
}

// PrincipalFromContext 取出接入层注入的 AI 客户端身份；ok=false 表示该
// 请求走了管理员会话分支（或未经过 AI 鉴权），调用方据此区分身份类型。
func PrincipalFromContext(ctx context.Context) (Principal, bool) {
	principal, ok := ctx.Value(principalKey{}).(Principal)
	return principal, ok
}

// clientFromRow 把库行解析成 Client；任何字段非法都返回错误，由调用方
// 决定"忽略该行"（缓存加载）还是"报错暴露"（管理列表），绝不把坏配置
// 当成有效授权。
func clientFromRow(row db.AiClient) (Client, error) {
	scopesRaw, err := decodeStringArray(row.ScopesJson)
	if err != nil {
		return Client{}, fmt.Errorf("客户端 %s 的 scopes 不是 JSON 字符串数组: %w", row.ID, err)
	}
	scopes, err := ParseScopes(scopesRaw)
	if err != nil {
		return Client{}, fmt.Errorf("客户端 %s: %w", row.ID, err)
	}
	deviceIDsRaw, err := decodeStringArray(row.DeviceIdsJson)
	if err != nil {
		return Client{}, fmt.Errorf("客户端 %s 的 device_ids 不是 JSON 字符串数组: %w", row.ID, err)
	}
	deviceIDs, err := normalizeDeviceIDs(deviceIDsRaw)
	if err != nil {
		return Client{}, fmt.Errorf("客户端 %s: %w", row.ID, err)
	}
	createdAt, err := parseTimestamp(row.CreatedAt)
	if err != nil {
		return Client{}, fmt.Errorf("客户端 %s 的 created_at 不合法: %w", row.ID, err)
	}
	expiresAt, err := parseNullTimestamp(row.ExpiresAt)
	if err != nil {
		return Client{}, fmt.Errorf("客户端 %s 的 expires_at 不合法: %w", row.ID, err)
	}
	revokedAt, err := parseNullTimestamp(row.RevokedAt)
	if err != nil {
		return Client{}, fmt.Errorf("客户端 %s 的 revoked_at 不合法: %w", row.ID, err)
	}
	lastUsedAt, err := parseNullTimestamp(row.LastUsedAt)
	if err != nil {
		return Client{}, fmt.Errorf("客户端 %s 的 last_used_at 不合法: %w", row.ID, err)
	}
	return Client{
		ID: row.ID, Label: row.Label, Scopes: scopes, DeviceIDs: deviceIDs,
		CreatedAt: createdAt, ExpiresAt: expiresAt, RevokedAt: revokedAt, LastUsedAt: lastUsedAt,
	}, nil
}

// decodeStringArray 解码 TEXT 列里的 JSON 字符串数组（scopes/device_ids 共用）。
func decodeStringArray(raw string) ([]string, error) {
	var values []string
	if err := json.Unmarshal([]byte(raw), &values); err != nil {
		return nil, err
	}
	return values, nil
}

// encodeStringArray 把字符串列表编码成库内 JSON（空列表写 []，不是 NULL）。
func encodeStringArray(values []string) (string, error) {
	raw, err := json.Marshal(values)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

// normalizeDeviceIDs 裁剪/校验/去重设备标签（保持输入顺序）。
func normalizeDeviceIDs(raw []string) ([]string, error) {
	if len(raw) > MaxDeviceIDs {
		return nil, fmt.Errorf("deviceIds 最多 %d 个", MaxDeviceIDs)
	}
	seen := make(map[string]struct{}, len(raw))
	deviceIDs := make([]string, 0, len(raw))
	for _, item := range raw {
		deviceID := strings.TrimSpace(item)
		if deviceID == "" {
			return nil, fmt.Errorf("deviceIds 不能包含空值")
		}
		if len([]rune(deviceID)) > MaxDeviceIDRunes {
			return nil, fmt.Errorf("deviceIds 单项超过 %d 字符", MaxDeviceIDRunes)
		}
		if _, ok := seen[deviceID]; ok {
			continue
		}
		seen[deviceID] = struct{}{}
		deviceIDs = append(deviceIDs, deviceID)
	}
	return deviceIDs, nil
}

// parseTimestamp 解析 store 统一时间戳（UTC RFC3339 毫秒）。
func parseTimestamp(value string) (time.Time, error) {
	parsed, err := time.Parse(store.TimestampLayout, value)
	if err != nil {
		return time.Time{}, err
	}
	return parsed.UTC(), nil
}

// parseNullTimestamp 解析可空时间戳列。
func parseNullTimestamp(value sql.NullString) (*time.Time, error) {
	if !value.Valid || value.String == "" {
		return nil, nil
	}
	parsed, err := parseTimestamp(value.String)
	if err != nil {
		return nil, err
	}
	return &parsed, nil
}

// formatNullTimestamp 把可空时间写成可空时间戳列。
func formatNullTimestamp(value *time.Time) sql.NullString {
	if value == nil {
		return sql.NullString{}
	}
	return sql.NullString{String: store.FormatTimestamp(*value), Valid: true}
}
