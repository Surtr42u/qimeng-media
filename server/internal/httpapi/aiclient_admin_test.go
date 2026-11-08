package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"qimeng-media/server/internal/aiauth"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/httpapi/gen"
)

// TestAIClientAdminEndpoints 覆盖凭据管理 HTTP 面：管理员会话可创建/列表/
// 轮换/撤销；AI 客户端即使只读也被拒绝访问管理与审计端点；轮换后旧 token
// 立即失效。
func TestAIClientAdminEndpoints(t *testing.T) {
	env := newAIHTTPEnv(t)
	status, body := env.request(t, http.MethodPost, "/api/v1/ai-clients", env.adminToken,
		strings.NewReader(`{"label":"console","scopes":["read"]}`), "application/json", nil)
	if status != http.StatusCreated {
		t.Fatalf("创建客户端 = %d %s，期望 201", status, body)
	}
	var secret gen.AiClientSecret
	if err := json.Unmarshal(body, &secret); err != nil {
		t.Fatalf("解析创建响应失败: %v", err)
	}
	if secret.Token == "" || secret.Client.Label != "console" || secret.Client.Id == "" {
		t.Fatalf("创建响应异常: %+v", secret)
	}
	clientID := secret.Client.Id

	status, body = env.request(t, http.MethodGet, "/api/v1/ai-clients", env.adminToken, nil, "", nil)
	if status != http.StatusOK {
		t.Fatalf("列表 = %d %s", status, body)
	}
	var list gen.AiClientList
	if err := json.Unmarshal(body, &list); err != nil || len(list.Items) != 1 || list.Items[0].Id != clientID {
		t.Fatalf("列表内容异常: %+v err=%v", list, err)
	}
	if len(list.Items[0].Scopes) != 1 || list.Items[0].Scopes[0] != gen.AiScopeRead {
		t.Fatalf("列表 scope 异常: %+v", list.Items[0].Scopes)
	}

	// 只读 AI 客户端可读业务端点，但不能读管理/审计面。
	if status, body = env.request(t, http.MethodGet, "/api/v1/libraries", secret.Token, nil, "", nil); status != http.StatusOK {
		t.Fatalf("read scope 读 libraries = %d %s", status, body)
	}
	if status, body = env.request(t, http.MethodGet, "/api/v1/ai-clients", secret.Token, nil, "", nil); status != http.StatusForbidden {
		t.Fatalf("AI 读管理面 = %d %s，期望 403", status, body)
	}
	if status, body = env.request(t, http.MethodGet, "/api/v1/ai-audit", secret.Token, nil, "", nil); status != http.StatusForbidden {
		t.Fatalf("AI 读审计面 = %d %s，期望 403", status, body)
	}

	status, body = env.request(t, http.MethodPost, "/api/v1/ai-clients/"+clientID+"/rotate", env.adminToken, nil, "", nil)
	if status != http.StatusOK {
		t.Fatalf("轮换 = %d %s", status, body)
	}
	var rotated gen.AiClientSecret
	if err := json.Unmarshal(body, &rotated); err != nil || rotated.Token == "" || rotated.Token == secret.Token {
		t.Fatalf("轮换响应异常: %+v err=%v", rotated, err)
	}
	if status, _ = env.request(t, http.MethodGet, "/api/v1/libraries", secret.Token, nil, "", nil); status != http.StatusUnauthorized {
		t.Fatalf("旧 token 轮换后 = %d，期望 401", status)
	}
	if status, body = env.request(t, http.MethodGet, "/api/v1/libraries", rotated.Token, nil, "", nil); status != http.StatusOK {
		t.Fatalf("新 token = %d %s，期望 200", status, body)
	}

	status, body = env.request(t, http.MethodDelete, "/api/v1/ai-clients/"+clientID, env.adminToken, nil, "", nil)
	if status != http.StatusOK {
		t.Fatalf("撤销 = %d %s", status, body)
	}
	var revoked gen.AiClient
	if err := json.Unmarshal(body, &revoked); err != nil || revoked.RevokedAt == nil {
		t.Fatalf("撤销响应异常: %+v err=%v", revoked, err)
	}
	if status, _ = env.request(t, http.MethodDelete, "/api/v1/ai-clients/"+clientID, env.adminToken, nil, "", nil); status != http.StatusOK {
		t.Fatalf("重复撤销 = %d，期望 200 幂等", status)
	}
	if status, _ = env.request(t, http.MethodGet, "/api/v1/libraries", rotated.Token, nil, "", nil); status != http.StatusUnauthorized {
		t.Fatalf("撤销后 token = %d，期望 401", status)
	}
}

// TestAIClientAuditQuery 覆盖管理员审计查询：AI 写请求被拒也入账，返回
// 元数据与稳定分页游标。
func TestAIClientAuditQuery(t *testing.T) {
	env := newAIHTTPEnv(t)
	client, token := env.createClient(t, "audit-reader", []aiauth.Scope{aiauth.ScopeRead}, nil)
	for _, payload := range []string{`{"name":"synthetic"}`, `{"name":"synthetic-2"}`} {
		if status, _ := env.request(t, http.MethodPost, "/api/v1/tags", token,
			strings.NewReader(payload), "application/json", nil); status != http.StatusForbidden {
			t.Fatalf("缺少 tag.write 的写请求 = %d，期望 403", status)
		}
	}
	status, body := env.request(t, http.MethodGet, "/api/v1/ai-audit?clientId="+client.ID+"&limit=1", env.adminToken, nil, "", nil)
	if status != http.StatusOK {
		t.Fatalf("审计查询 = %d %s", status, body)
	}
	var page gen.AiAuditList
	if err := json.Unmarshal(body, &page); err != nil {
		t.Fatalf("解析审计失败: %v", err)
	}
	if len(page.Items) != 1 || page.NextCursor == nil || *page.NextCursor == "" {
		t.Fatalf("第一页 = %+v，期望 1 项且有游标", page)
	}
	item := page.Items[0]
	if item.ClientId != client.ID || item.Action != "tag.write" || item.StatusCode != http.StatusForbidden || item.Method != http.MethodPost {
		t.Fatalf("审计条目异常: %+v", item)
	}
	status, body = env.request(t, http.MethodGet, "/api/v1/ai-audit?clientId="+client.ID+"&limit=1&cursor="+*page.NextCursor, env.adminToken, nil, "", nil)
	if status != http.StatusOK {
		t.Fatalf("审计第二页 = %d %s", status, body)
	}
	var second gen.AiAuditList
	if err := json.Unmarshal(body, &second); err != nil || len(second.Items) != 1 || second.Items[0].Id == item.Id {
		t.Fatalf("第二页异常: %+v err=%v", second, err)
	}
}

// TestAIClientRateLimit 覆盖按客户端限流：突发用尽 429 RATE_LIMITED，且
// 只有 AI 客户端受此限流（管理员会话不受影响）。
func TestAIClientRateLimit(t *testing.T) {
	env := newAIHTTPEnvWithConfig(t, &config.AIConfig{RateLimitPerMinute: 60, RateLimitBurst: 2})
	_, token := env.createClient(t, "rate-limited", []aiauth.Scope{aiauth.ScopeRead}, nil)
	for i := 0; i < 2; i++ {
		if status, body := env.request(t, http.MethodGet, "/api/v1/libraries", token, nil, "", nil); status != http.StatusOK {
			t.Fatalf("第 %d 次请求 = %d %s，期望 200", i+1, status, body)
		}
	}
	status, body := env.request(t, http.MethodGet, "/api/v1/libraries", token, nil, "", nil)
	if status != http.StatusTooManyRequests || aiErrorCode(t, body) != codeRateLimited {
		t.Fatalf("超限请求 = %d %s，期望 429 RATE_LIMITED", status, body)
	}
	if status, body := env.request(t, http.MethodGet, "/api/v1/libraries", env.adminToken, nil, "", nil); status != http.StatusOK {
		t.Fatalf("管理员会话不应被 AI 限流影响: %d %s", status, body)
	}
}

// TestRequiredAIScopeAssetSubroutes 覆盖资产子路由的映射顺序：解绑标签是
// asset.write，删除资产才是 asset.delete；删除回收站/清空同样走 asset.delete。
func TestRequiredAIScopeAssetSubroutes(t *testing.T) {
	cases := []struct {
		method string
		path   string
		scope  aiauth.Scope
		mapped bool
	}{
		{http.MethodDelete, "/api/v1/assets/a1/tags/alice", aiauth.ScopeAssetWrite, true},
		{http.MethodDelete, "/api/v1/assets/a1/timeline-tags/t1", aiauth.ScopeAssetWrite, true},
		{http.MethodPut, "/api/v1/assets/a1/tags", aiauth.ScopeAssetWrite, true},
		{http.MethodPost, "/api/v1/assets/a1/timeline-tags", aiauth.ScopeAssetWrite, true},
		{http.MethodDelete, "/api/v1/assets/a1", aiauth.ScopeAssetDelete, true},
		{http.MethodDelete, "/api/v1/trash/t1", aiauth.ScopeAssetDelete, true},
		{http.MethodDelete, "/api/v1/trash", aiauth.ScopeAssetDelete, true},
		{http.MethodPost, "/api/v1/trash/t1/restore", aiauth.ScopeTrashRestore, true},
		{http.MethodPost, "/api/v1/assets/upload", aiauth.ScopeAssetUpload, true},
		{http.MethodPost, "/api/v1/tags", aiauth.ScopeTagWrite, true},
		{http.MethodPut, "/api/v1/sources/custom", aiauth.ScopeSourceWrite, true},
		{http.MethodPost, "/api/v1/libraries", aiauth.ScopeLibraryWrite, true},
		{http.MethodPost, "/api/v1/maintenance/executor/claim", aiauth.ScopeMaintenanceExecute, true},
		{http.MethodPut, "/api/v1/config", "", false},
		{http.MethodGet, "/api/v1/ai-clients", "", false},
		{http.MethodGet, "/api/v1/ai-audit", "", false},
	}
	for _, tc := range cases {
		scope, _, mapped := requiredAIScope(tc.method, tc.path)
		if mapped != tc.mapped || scope != tc.scope {
			t.Fatalf("%s %s = (%q,%v)，期望 (%q,%v)", tc.method, tc.path, scope, mapped, tc.scope, tc.mapped)
		}
	}
}
