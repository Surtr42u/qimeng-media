package aiauth

import (
	"context"
	"errors"
	"testing"
	"time"
)

// TestListAuditFiltersAndCursor 覆盖审计查询的过滤、新建倒序与 keyset 分页：
// 审计是运营证据，分页错误会导致重复/漏看，必须逐页校验。
func TestListAuditFiltersAndCursor(t *testing.T) {
	svc, _, clock := newTestService(t)
	ctx := context.Background()
	clientA, _, err := svc.CreateClient(ctx, CreateClientInput{
		Label: "audit-a", Scopes: []Scope{ScopeRead},
	})
	if err != nil {
		t.Fatalf("创建客户端失败: %v", err)
	}
	clientB, _, err := svc.CreateClient(ctx, CreateClientInput{
		Label: "audit-b", Scopes: []Scope{ScopeRead},
	})
	if err != nil {
		t.Fatalf("创建客户端失败: %v", err)
	}
	for i := 0; i < 3; i++ {
		if err := svc.RecordAudit(ctx, AuditEntry{
			ClientID: clientA.ID, Method: "POST", Path: "/api/v1/authors",
			Action: "author.write", StatusCode: 200, RequestID: "req-a",
		}); err != nil {
			t.Fatalf("写审计失败: %v", err)
		}
	}
	if err := svc.RecordAudit(ctx, AuditEntry{
		ClientID: clientB.ID, Method: "DELETE", Path: "/api/v1/assets/x",
		Action: "asset.delete", StatusCode: 403,
	}); err != nil {
		t.Fatalf("写审计失败: %v", err)
	}
	clock.Advance(time.Second)

	page, err := svc.ListAudit(ctx, AuditFilter{ClientID: clientA.ID, Limit: 2})
	if err != nil {
		t.Fatalf("查询审计失败: %v", err)
	}
	if len(page.Items) != 2 || page.NextCursor == "" {
		t.Fatalf("第一页 = %d 项 cursor=%q，期望 2 项且有游标", len(page.Items), page.NextCursor)
	}
	next, err := svc.ListAudit(ctx, AuditFilter{ClientID: clientA.ID, Limit: 2, Cursor: page.NextCursor})
	if err != nil {
		t.Fatalf("第二页失败: %v", err)
	}
	if len(next.Items) != 1 || next.NextCursor != "" {
		t.Fatalf("第二页 = %d 项 cursor=%q，期望 1 项且到底", len(next.Items), next.NextCursor)
	}
	seen := map[int64]struct{}{}
	for _, item := range append(page.Items, next.Items...) {
		if _, dup := seen[item.ID]; dup {
			t.Fatalf("分页重复返回审计 id=%d", item.ID)
		}
		seen[item.ID] = struct{}{}
		if item.ClientID != clientA.ID {
			t.Fatalf("客户端过滤失效: %+v", item)
		}
	}
	byAction, err := svc.ListAudit(ctx, AuditFilter{ActionPrefix: "asset."})
	if err != nil {
		t.Fatalf("动作前缀过滤失败: %v", err)
	}
	if len(byAction.Items) != 1 || byAction.Items[0].Action != "asset.delete" || byAction.Items[0].StatusCode != 403 {
		t.Fatalf("动作前缀过滤结果 = %+v", byAction.Items)
	}
	if _, err := svc.ListAudit(ctx, AuditFilter{Cursor: "not-a-cursor"}); !errors.Is(err, ErrInvalidCursor) {
		t.Fatalf("非法游标错误 = %v，期望 ErrInvalidCursor", err)
	}
}

// TestRateLimiterBurstAndRefill 覆盖按客户端令牌桶：突发用尽后 429 语义，
// 时间推进后按每分钟速率补充。
func TestRateLimiterBurstAndRefill(t *testing.T) {
	svc, _, clock := newTestService(t)
	enabled, err := New(Options{
		Conn: svc.conn, Queries: svc.q, Now: clock.Now, Logger: svc.logger,
		RateLimitPerMinute: 60, RateLimitBurst: 2,
	})
	if err != nil {
		t.Fatalf("组装限流服务失败: %v", err)
	}
	if _, ok := enabled.AllowRequest("client-a"); !ok {
		t.Fatal("第 1 次请求应放行")
	}
	if _, ok := enabled.AllowRequest("client-a"); !ok {
		t.Fatal("第 2 次请求应放行（突发=2）")
	}
	retry, ok := enabled.AllowRequest("client-a")
	if ok || retry < time.Second {
		t.Fatalf("第 3 次请求 = (%v,%v)，期望拒绝且 Retry-After>=1s", retry, ok)
	}
	if _, ok := enabled.AllowRequest("client-b"); !ok {
		t.Fatal("不同客户端应独立计数")
	}
	clock.Advance(time.Second)
	if _, ok := enabled.AllowRequest("client-a"); !ok {
		t.Fatal("推进 1 秒后应补充 1 个令牌（60/分钟）")
	}
	// 未配置限流时恒放行（本地单机调试语义）。
	if _, ok := svc.AllowRequest("anything"); !ok {
		t.Fatal("未启用限流时应恒放行")
	}
}
