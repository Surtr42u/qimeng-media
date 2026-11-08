package aiauth

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// testClock 是可控时钟：过期/缓存 TTL 用例不依赖真实 sleep。
type testClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *testClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

// newTestService 用临时 SQLite + 真实 migration 组装服务（与生产同源）。
func newTestService(t *testing.T) (*Service, *db.Queries, *testClock) {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "aiauth-test.db"))
	if err != nil {
		t.Fatalf("打开临时库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移临时库失败: %v", err)
	}
	clock := &testClock{now: time.Date(2026, 11, 7, 0, 0, 0, 0, time.UTC)}
	q := db.New(conn)
	service, err := New(Options{
		Conn: conn, Queries: q, Now: clock.Now,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatalf("组装 aiauth 服务失败: %v", err)
	}
	return service, q, clock
}

func mustCreateClient(t *testing.T, service *Service, label string, scopes []Scope, devices []string) (Client, string) {
	t.Helper()
	client, token, err := service.CreateClient(context.Background(), CreateClientInput{
		Label: label, Scopes: scopes, DeviceIDs: devices,
	})
	if err != nil {
		t.Fatalf("创建 AI 客户端失败: %v", err)
	}
	if token == "" {
		t.Fatal("创建客户端未返回明文 token")
	}
	return client, token
}

func TestParseScopes(t *testing.T) {
	scopes, err := ParseScopes([]string{" read ", "author.write", "read"})
	if err != nil {
		t.Fatalf("解析合法 scope 失败: %v", err)
	}
	if len(scopes) != 2 || scopes[0] != ScopeRead || scopes[1] != ScopeAuthorWrite {
		t.Fatalf("scope 去重/顺序异常: %+v", scopes)
	}
	if _, err := ParseScopes(nil); !errors.Is(err, ErrNoScopes) {
		t.Fatalf("空 scope 列表期望 ErrNoScopes，得到 %v", err)
	}
	if _, err := ParseScopes([]string{"read", "bogus"}); !errors.Is(err, ErrUnknownScope) {
		t.Fatalf("未知 scope 期望 ErrUnknownScope，得到 %v", err)
	}
	if _, err := ParseScope("READ"); !errors.Is(err, ErrUnknownScope) {
		t.Fatalf("scope 大小写敏感（未知值拒绝），得到 %v", err)
	}
	set := NewScopeSet(scopes)
	if !set.Has(ScopeRead) || set.Has(ScopeAdmin) {
		t.Fatalf("ScopeSet 匹配异常: %+v", set.List())
	}
}

func TestCreateAuthenticateAndScopeMatch(t *testing.T) {
	service, q, _ := newTestService(t)
	ctx := context.Background()
	client, token := mustCreateClient(t, service, "executor-1",
		[]Scope{ScopeRead, ScopeAuthorWrite, ScopeRead}, []string{"phone-main"})
	if len(client.Scopes) != 2 {
		t.Fatalf("重复 scope 未去重: %+v", client.Scopes)
	}
	if !client.HasScope(ScopeAuthorWrite) || client.HasScope(ScopeAdmin) {
		t.Fatal("客户端 scope 匹配异常")
	}
	// 库内只存哈希，明文 token 绝不落库。
	row, err := q.GetAiClientByTokenHash(ctx, auth.TokenHash(token))
	if err != nil {
		t.Fatalf("按 token 哈希查库失败: %v", err)
	}
	if row.ID != client.ID || row.TokenHash == token {
		t.Fatalf("凭据落库异常: id=%s hash=%q", row.ID, row.TokenHash)
	}
	if !strings.Contains(row.ScopesJson, "author.write") || !strings.Contains(row.DeviceIdsJson, "phone-main") {
		t.Fatalf("scope/device 列异常: %s / %s", row.ScopesJson, row.DeviceIdsJson)
	}

	authenticated, ok := service.Authenticate(ctx, token)
	if !ok || authenticated.ID != client.ID {
		t.Fatalf("合法 token 鉴权失败: ok=%v id=%s", ok, authenticated.ID)
	}
	if !authenticated.HasScope(ScopeRead) {
		t.Fatal("鉴权结果缺少 scope")
	}
	for name, candidate := range map[string]string{
		"wrong": token + "0",
		"empty": "",
		"space": " ",
	} {
		if _, ok := service.Authenticate(ctx, candidate); ok {
			t.Fatalf("%s token 不应通过鉴权", name)
		}
	}
}

func TestExpiredRevokedAndRotatedClientsRejected(t *testing.T) {
	service, _, clock := newTestService(t)
	ctx := context.Background()

	// 过期：时钟前进超过有效期后拒绝。
	expiresAt := clock.Now().Add(time.Hour)
	expired, expiredToken, err := service.CreateClient(ctx, CreateClientInput{
		Label: "expiring", Scopes: []Scope{ScopeRead}, ExpiresAt: &expiresAt,
	})
	if err != nil {
		t.Fatalf("创建带有效期客户端失败: %v", err)
	}
	if _, ok := service.Authenticate(ctx, expiredToken); !ok {
		t.Fatal("未过期客户端鉴权失败")
	}
	clock.Advance(2 * time.Hour)
	if _, ok := service.Authenticate(ctx, expiredToken); ok {
		t.Fatalf("过期客户端仍通过鉴权: %s", expired.ID)
	}

	// 撤销：重复撤销幂等。
	revoked, revokedToken := mustCreateClient(t, service, "revoked", []Scope{ScopeRead}, nil)
	if err := service.RevokeClient(ctx, revoked.ID); err != nil {
		t.Fatalf("撤销客户端失败: %v", err)
	}
	if err := service.RevokeClient(ctx, revoked.ID); err != nil {
		t.Fatalf("重复撤销应幂等: %v", err)
	}
	if _, ok := service.Authenticate(ctx, revokedToken); ok {
		t.Fatal("已撤销客户端仍通过鉴权")
	}
	// 缓存整体刷新后依旧拒绝（撤销状态在库里，不只在内存）。
	if err := service.Reload(ctx); err != nil {
		t.Fatalf("重新加载客户端失败: %v", err)
	}
	if _, ok := service.Authenticate(ctx, revokedToken); ok {
		t.Fatal("重新加载后已撤销客户端仍通过鉴权")
	}

	// 轮换：旧 token 立即失效，新 token 可用；已撤销客户端拒绝轮换。
	rotating, oldToken := mustCreateClient(t, service, "rotating", []Scope{ScopeRead}, nil)
	rotated, newToken, err := service.RotateClient(ctx, rotating.ID)
	if err != nil || newToken == oldToken || rotated.ID != rotating.ID {
		t.Fatalf("轮换失败: client=%+v err=%v", rotated, err)
	}
	if _, ok := service.Authenticate(ctx, oldToken); ok {
		t.Fatal("轮换后旧 token 仍可用")
	}
	if _, ok := service.Authenticate(ctx, newToken); !ok {
		t.Fatal("轮换后新 token 不可用")
	}
	if _, _, err := service.RotateClient(ctx, revoked.ID); !errors.Is(err, ErrClientRevoked) {
		t.Fatalf("已撤销客户端轮换期望 ErrClientRevoked，得到 %v", err)
	}
	if _, _, err := service.RotateClient(ctx, "00000000-0000-4000-8000-000000000000"); !errors.Is(err, ErrClientNotFound) {
		t.Fatalf("缺失客户端轮换期望 ErrClientNotFound，得到 %v", err)
	}
}

func TestUnknownScopeRowFailsClosed(t *testing.T) {
	service, q, _ := newTestService(t)
	ctx := context.Background()
	token, err := auth.GenerateToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	// 直接写一行非法 scope（模拟外部改库/手工写入）：加载时跳过，鉴权拒绝。
	if err := q.CreateAiClient(ctx, db.CreateAiClientParams{
		ID: "broken-client", Label: "broken", TokenHash: auth.TokenHash(token),
		ScopesJson: "[\"bogus\"]", DeviceIdsJson: "[]",
		CreatedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatalf("写入非法客户端行失败: %v", err)
	}
	if err := service.Reload(ctx); err != nil {
		t.Fatalf("重新加载不应因坏行失败: %v", err)
	}
	if _, ok := service.Authenticate(ctx, token); ok {
		t.Fatal("非法 scope 的客户端不应通过鉴权（fail-closed）")
	}
	if _, err := service.ListClients(ctx); err == nil {
		t.Fatal("管理列表应暴露非法 scope 行")
	}
}

func TestCacheReloadsOnMissAfterTTL(t *testing.T) {
	service, q, clock := newTestService(t)
	ctx := context.Background()
	token, err := auth.GenerateToken()
	if err != nil {
		t.Fatalf("生成 token 失败: %v", err)
	}
	// 绕过服务直接写库：初始缓存（构造时）里没有这个客户端。
	if err := q.CreateAiClient(ctx, db.CreateAiClientParams{
		ID: "late-client", Label: "late", TokenHash: auth.TokenHash(token),
		ScopesJson: "[\"read\"]", DeviceIdsJson: "[]",
		CreatedAt: store.FormatTimestamp(clock.Now()),
	}); err != nil {
		t.Fatalf("写入客户端失败: %v", err)
	}
	if _, ok := service.Authenticate(ctx, token); ok {
		t.Fatal("缓存未过期时不应命中新客户端")
	}
	clock.Advance(DefaultCacheTTL + time.Second)
	if _, ok := service.Authenticate(ctx, token); !ok {
		t.Fatal("缓存过期重载后应命中新客户端")
	}
}

func TestClientAndPrincipalDeviceBinding(t *testing.T) {
	service, _, _ := newTestService(t)
	client, _ := mustCreateClient(t, service, "device-executor",
		[]Scope{ScopeDeviceApply}, []string{"phone-main", "phone-b"})
	if !client.AllowsDevice("phone-main") || !client.AllowsDevice(" phone-b ") {
		t.Fatal("绑定设备应允许访问")
	}
	if client.AllowsDevice("phone-a") || client.AllowsDevice("") || client.AllowsDevice("PHONE-MAIN") {
		t.Fatal("未绑定设备/空标签/大小写变体应拒绝")
	}
	noDevice, _ := mustCreateClient(t, service, "no-device", []Scope{ScopeRead}, nil)
	if noDevice.AllowsDevice("phone-main") {
		t.Fatal("未绑定任何设备时不应允许设备端点")
	}
	principal := Principal{ClientID: client.ID, Scopes: client.Scopes, DeviceIDs: client.DeviceIDs}
	if !principal.AllowsDevice("phone-main") || principal.AllowsDevice("phone-a") {
		t.Fatal("Principal 设备匹配异常")
	}
	if !principal.HasScope(ScopeDeviceApply) || principal.HasScope(ScopeAdmin) {
		t.Fatal("Principal scope 匹配异常")
	}
}

func TestRecordAudit(t *testing.T) {
	service, q, _ := newTestService(t)
	ctx := context.Background()
	longAction := strings.Repeat("a", MaxAuditActionRunes+50)
	if err := service.RecordAudit(ctx, AuditEntry{
		ClientID: "client-1", Method: "POST", Path: "/api/v1/maintenance/jobs",
		Action: longAction, StatusCode: 201, RequestID: "req-1",
	}); err != nil {
		t.Fatalf("写入审计失败: %v", err)
	}
	rows, err := q.ListAiAuditByClient(ctx, db.ListAiAuditByClientParams{ClientID: "client-1", Limit: 10})
	if err != nil || len(rows) != 1 {
		t.Fatalf("读取审计失败: rows=%d err=%v", len(rows), err)
	}
	row := rows[0]
	if row.Method != "POST" || row.Path != "/api/v1/maintenance/jobs" ||
		row.StatusCode != 201 || row.RequestID != "req-1" || row.CreatedAt == "" {
		t.Fatalf("审计字段异常: %+v", row)
	}
	if len([]rune(row.Action)) != MaxAuditActionRunes {
		t.Fatalf("审计 action 未按上限截断: %d", len([]rune(row.Action)))
	}
	if err := service.RecordAudit(ctx, AuditEntry{Method: "POST"}); err == nil {
		t.Fatal("缺少 clientId 的审计应报错")
	}
}

func TestCreateClientRejectsInvalidInput(t *testing.T) {
	service, _, clock := newTestService(t)
	ctx := context.Background()
	past := clock.Now().Add(-time.Hour)
	cases := map[string]CreateClientInput{
		"empty-label":   {Label: " ", Scopes: []Scope{ScopeRead}},
		"long-label":    {Label: strings.Repeat("标", MaxLabelRunes+1), Scopes: []Scope{ScopeRead}},
		"empty-scopes":  {Label: "ok"},
		"unknown-scope": {Label: "ok", Scopes: []Scope{Scope("bogus")}},
		"bad-expires":   {Label: "ok", Scopes: []Scope{ScopeRead}, ExpiresAt: &past},
		"empty-device":  {Label: "ok", Scopes: []Scope{ScopeDeviceApply}, DeviceIDs: []string{" "}},
	}
	for name, input := range cases {
		if _, _, err := service.CreateClient(ctx, input); !errors.Is(err, ErrInvalidInput) {
			t.Fatalf("%s 期望 ErrInvalidInput，得到 %v", name, err)
		}
	}
}
