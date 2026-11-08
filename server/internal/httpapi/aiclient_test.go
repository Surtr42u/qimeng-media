package httpapi

// 独立 AI 客户端凭据的 HTTP 集成测试：管理员会话兼容、scope 允许/拒绝、
// 写审计（成功与拒绝都记录）、设备级绑定隔离，以及设备结果包领取/回传的
// 审计动作标签（ADR-0057 P1）。使用临时 SQLite + 合成数据，不访问真实库。

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"qimeng-media/server/internal/aiauth"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

// aiHTTPEnv 是 AI 凭据测试的最小完整服务端环境（与生产同款 New 装配 +
// 一次 admin setup 拿管理员 token）。
type aiHTTPEnv struct {
	s          *Server
	ts         *httptest.Server
	adminToken string
	q          *db.Queries
}

func newAIHTTPEnv(t *testing.T) *aiHTTPEnv {
	t.Helper()
	return newAIHTTPEnvWithConfig(t, nil)
}

// newAIHTTPEnvWithConfig 允许用例覆盖 AI 限流等配置（其余装配与生产同款）。
func newAIHTTPEnvWithConfig(t *testing.T, aiCfg *config.AIConfig) *aiHTTPEnv {
	t.Helper()
	dataDir := t.TempDir()
	conn, err := store.Open(filepath.Join(dataDir, "test.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移测试库失败: %v", err)
	}
	q := db.New(conn)
	cfg := &config.Config{DataDir: dataDir}
	if aiCfg != nil {
		cfg.AI = *aiCfg
	}
	apisrv, err := New(Deps{
		Conn: conn, Queries: q, Bus: events.NewBus(nil, 0), Cfg: cfg,
		Thumbs:      thumbnail.NewGenerator(dataDir, nil, thumbnail.Options{}),
		MediaSecret: []byte("test-secret-0123456789abcdef0123456789"),
	})
	if err != nil {
		t.Fatalf("组装服务失败: %v", err)
	}
	ts := httptest.NewServer(apisrv.Handler())
	t.Cleanup(ts.Close)

	resp, err := http.Post(ts.URL+"/api/v1/auth/setup", "application/json",
		strings.NewReader("{\"password\":\"ai-test-password\"}"))
	if err != nil {
		t.Fatalf("setup 请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("setup 期望 201，得到 %d", resp.StatusCode)
	}
	var tokenBody map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&tokenBody); err != nil || tokenBody["token"] == "" {
		t.Fatalf("setup 未返回 token: %v", err)
	}
	return &aiHTTPEnv{s: apisrv, ts: ts, adminToken: tokenBody["token"], q: q}
}

// createClient 直接经 aiauth 服务创建客户端（管理面等价于本地 CLI）。
func (e *aiHTTPEnv) createClient(t *testing.T, label string, scopes []aiauth.Scope, devices []string) (aiauth.Client, string) {
	t.Helper()
	client, token, err := e.s.aiAuth.CreateClient(context.Background(), aiauth.CreateClientInput{
		Label: label, Scopes: scopes, DeviceIDs: devices,
	})
	if err != nil {
		t.Fatalf("创建 AI 客户端失败: %v", err)
	}
	return client, token
}

// request 发一次请求；token 为空表示不带 Authorization。
func (e *aiHTTPEnv) request(t *testing.T, method, path, token string, body io.Reader, contentType string, headers map[string]string) (int, []byte) {
	t.Helper()
	req, err := http.NewRequest(method, e.ts.URL+path, body)
	if err != nil {
		t.Fatalf("构造请求失败: %v", err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	for name, value := range headers {
		req.Header.Set(name, value)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求 %s %s 失败: %v", method, path, err)
	}
	defer func() { _ = resp.Body.Close() }()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("读取响应体失败: %v", err)
	}
	return resp.StatusCode, data
}

// audits 读取某客户端最近审计行（按 created_at DESC, id DESC）。
func (e *aiHTTPEnv) audits(t *testing.T, clientID string) []db.AiAudit {
	t.Helper()
	rows, err := e.q.ListAiAuditByClient(context.Background(), db.ListAiAuditByClientParams{
		ClientID: clientID, Limit: 50,
	})
	if err != nil {
		t.Fatalf("读取审计失败: %v", err)
	}
	return rows
}

// errorCode 解析协议 Error.code。
func aiErrorCode(t *testing.T, body []byte) string {
	t.Helper()
	var apiErr gen.Error
	if err := json.Unmarshal(body, &apiErr); err != nil {
		t.Fatalf("解析错误响应失败: %v", err)
	}
	return apiErr.Code
}

func TestAIClientScopeAllowAndWriteAudit(t *testing.T) {
	env := newAIHTTPEnv(t)
	client, token := env.createClient(t, "executor", []aiauth.Scope{
		aiauth.ScopeRead, aiauth.ScopeMaintenanceRead, aiauth.ScopeMaintenanceWrite,
	}, nil)

	createBody := "{\"kind\":\"custom\",\"idempotencyKey\":\"ai-idem-1\"}"
	status, body := env.request(t, http.MethodPost, "/api/v1/maintenance/jobs", token,
		strings.NewReader(createBody), "application/json", map[string]string{"X-Request-Id": "ai-req-1"})
	if status != http.StatusCreated {
		t.Fatalf("AI 创建维护任务期望 201，得到 %d: %s", status, body)
	}
	if status, body = env.request(t, http.MethodGet, "/api/v1/maintenance/jobs", token, nil, "", nil); status != http.StatusOK {
		t.Fatalf("AI 读维护任务列表期望 200，得到 %d: %s", status, body)
	}
	if status, body = env.request(t, http.MethodGet, "/api/v1/libraries", token, nil, "", nil); status != http.StatusOK {
		t.Fatalf("AI read scope 读库列表期望 200，得到 %d: %s", status, body)
	}

	// 审计：一条写请求、请求 ID 透传、动作=所需 scope。
	rows := env.audits(t, client.ID)
	if len(rows) != 1 {
		t.Fatalf("AI 写请求审计条数 = %d，期望 1", len(rows))
	}
	audit := rows[0]
	if audit.Method != http.MethodPost || audit.Path != "/api/v1/maintenance/jobs" ||
		audit.Action != "maintenance.write" || audit.StatusCode != http.StatusCreated ||
		audit.RequestID != "ai-req-1" {
		t.Fatalf("审计字段异常: %+v", audit)
	}

	// 管理员会话不受 scope 限制，且不写 AI 审计表。
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/jobs", env.adminToken,
		strings.NewReader("{\"kind\":\"custom\",\"idempotencyKey\":\"admin-idem-1\"}"), "application/json", nil)
	if status != http.StatusCreated {
		t.Fatalf("管理员创建维护任务期望 201，得到 %d: %s", status, body)
	}
	if rows = env.audits(t, client.ID); len(rows) != 1 {
		t.Fatalf("管理员请求不应写入 AI 审计，审计条数 = %d", len(rows))
	}
}

func TestAIClientDeniedWritesAudited(t *testing.T) {
	env := newAIHTTPEnv(t)
	client, token := env.createClient(t, "readonly", []aiauth.Scope{aiauth.ScopeRead}, nil)

	// 已映射但缺 scope：maintenance.write。
	status, body := env.request(t, http.MethodPost, "/api/v1/maintenance/jobs", token,
		strings.NewReader("{\"kind\":\"custom\",\"idempotencyKey\":\"denied-1\"}"), "application/json", nil)
	if status != http.StatusForbidden || aiErrorCode(t, body) != codeForbiddenScope {
		t.Fatalf("缺少 maintenance.write 期望 403 FORBIDDEN_SCOPE，得到 %d: %s", status, body)
	}
	// 已映射但缺 scope：tag.write（2026-11-08 起标签写面向 AI 开放）。
	status, body = env.request(t, http.MethodPost, "/api/v1/tags", token,
		strings.NewReader("{\"name\":\"synthetic\"}"), "application/json", nil)
	if status != http.StatusForbidden || aiErrorCode(t, body) != codeForbiddenScope {
		t.Fatalf("缺少 tag.write 期望 403 FORBIDDEN_SCOPE，得到 %d: %s", status, body)
	}
	// 仍未映射的写请求（配置修改仅限管理员）：默认拒绝，审计动作 admin。
	status, body = env.request(t, http.MethodPut, "/api/v1/config", token,
		strings.NewReader("{}"), "application/json", nil)
	if status != http.StatusForbidden || aiErrorCode(t, body) != codeForbiddenScope {
		t.Fatalf("未映射写请求期望 403 FORBIDDEN_SCOPE，得到 %d: %s", status, body)
	}
	// 读请求缺 maintenance.read：403 且不写审计（审计只覆盖写请求）。
	status, body = env.request(t, http.MethodGet, "/api/v1/maintenance/jobs", token, nil, "", nil)
	if status != http.StatusForbidden {
		t.Fatalf("缺 maintenance.read 读取期望 403，得到 %d: %s", status, body)
	}

	rows := env.audits(t, client.ID)
	if len(rows) != 3 {
		t.Fatalf("被拒绝写请求审计条数 = %d，期望 3", len(rows))
	}
	actions := map[string]db.AiAudit{rows[0].Action: rows[0], rows[1].Action: rows[1], rows[2].Action: rows[2]}
	if entry, ok := actions["maintenance.write"]; !ok || entry.StatusCode != http.StatusForbidden {
		t.Fatalf("缺少 maintenance.write 拒绝审计: %+v", actions)
	}
	if entry, ok := actions["tag.write"]; !ok || entry.StatusCode != http.StatusForbidden {
		t.Fatalf("缺少 tag.write 的拒绝审计缺失: %+v", actions)
	}
	if entry, ok := actions["admin"]; !ok || entry.StatusCode != http.StatusForbidden {
		t.Fatalf("未映射写请求审计动作应为 admin: %+v", actions)
	}
}

func TestAIClientTokenRotationAndRevocation(t *testing.T) {
	env := newAIHTTPEnv(t)
	client, token := env.createClient(t, "rotating", []aiauth.Scope{aiauth.ScopeRead}, nil)
	if status, _ := env.request(t, http.MethodGet, "/api/v1/libraries", token, nil, "", nil); status != http.StatusOK {
		t.Fatalf("轮换前 token 期望 200，得到 %d", status)
	}

	_, newToken, err := env.s.aiAuth.RotateClient(context.Background(), client.ID)
	if err != nil {
		t.Fatalf("轮换客户端失败: %v", err)
	}
	if status, body := env.request(t, http.MethodGet, "/api/v1/libraries", token, nil, "", nil); status != http.StatusUnauthorized {
		t.Fatalf("旧 token 轮换后期望 401，得到 %d: %s", status, body)
	}
	if status, _ := env.request(t, http.MethodGet, "/api/v1/libraries", newToken, nil, "", nil); status != http.StatusOK {
		t.Fatalf("新 token 期望 200，得到 %d", status)
	}

	if err := env.s.aiAuth.RevokeClient(context.Background(), client.ID); err != nil {
		t.Fatalf("撤销客户端失败: %v", err)
	}
	if status, body := env.request(t, http.MethodGet, "/api/v1/libraries", newToken, nil, "", nil); status != http.StatusUnauthorized {
		t.Fatalf("撤销后 token 期望 401，得到 %d: %s", status, body)
	}
}

func TestAdminAndInvalidBearerUnchanged(t *testing.T) {
	env := newAIHTTPEnv(t)
	if status, body := env.request(t, http.MethodGet, "/api/v1/libraries", "", nil, "", nil); status != http.StatusUnauthorized {
		t.Fatalf("无 token 期望 401，得到 %d: %s", status, body)
	}
	if status, body := env.request(t, http.MethodGet, "/api/v1/libraries", "not-a-real-token", nil, "", nil); status != http.StatusUnauthorized {
		t.Fatalf("非法 token 期望 401，得到 %d: %s", status, body)
	}
	if status, body := env.request(t, http.MethodGet, "/api/v1/maintenance/jobs", env.adminToken, nil, "", nil); status != http.StatusOK {
		t.Fatalf("管理员会话期望 200，得到 %d: %s", status, body)
	}
}

func TestAIClientDeviceBindingIsolation(t *testing.T) {
	env := newAIHTTPEnv(t)
	// 管理员为 phone-b 登记一个结果包。
	master := deviceApplyMaster(64)
	form, contentType := deviceApplyForm(t, deviceApplyManifest("phone-b", master, nil), master, nil)
	status, body := env.request(t, http.MethodPost, "/api/v1/device/apply-tasks", env.adminToken, form, contentType, nil)
	if status != http.StatusCreated {
		t.Fatalf("管理员登记结果包期望 201，得到 %d: %s", status, body)
	}
	var adminTask gen.DeviceApplyTask
	if err := json.Unmarshal(body, &adminTask); err != nil {
		t.Fatalf("解析设备任务失败: %v", err)
	}

	// 只绑定 phone-a 的 AI 客户端：列表按设备过滤。
	_, phoneAToken := env.createClient(t, "phone-a-executor", []aiauth.Scope{aiauth.ScopeDeviceApply}, []string{"phone-a"})
	if status, body := env.request(t, http.MethodGet, "/api/v1/device/apply-tasks?targetDevice=phone-a", phoneAToken, nil, "", nil); status != http.StatusOK {
		t.Fatalf("AI 查询自己设备列表期望 200，得到 %d: %s", status, body)
	}
	if status, body := env.request(t, http.MethodGet, "/api/v1/device/apply-tasks?targetDevice=phone-b", phoneAToken, nil, "", nil); status != http.StatusForbidden {
		t.Fatalf("AI 查询未绑定设备列表期望 403，得到 %d: %s", status, body)
	}
	taskPath := "/api/v1/device/apply-tasks/" + adminTask.Id.String()
	if status, body := env.request(t, http.MethodGet, taskPath, phoneAToken, nil, "", nil); status != http.StatusForbidden {
		t.Fatalf("AI 读取未绑定设备任务期望 403，得到 %d: %s", status, body)
	}
	if status, body := env.request(t, http.MethodPost, taskPath+"/claim", phoneAToken, nil, "", nil); status != http.StatusForbidden {
		t.Fatalf("AI 领取未绑定设备任务期望 403，得到 %d: %s", status, body)
	}
	if status, body := env.request(t, http.MethodPost, taskPath+"/result", phoneAToken,
		strings.NewReader("{\"status\":\"applied\"}"), "application/json", nil); status != http.StatusForbidden {
		t.Fatalf("AI 回传未绑定设备任务期望 403，得到 %d: %s", status, body)
	}
	// 越权 claim 不得改变任务状态。
	status, body = env.request(t, http.MethodGet, taskPath, env.adminToken, nil, "", nil)
	if status != http.StatusOK {
		t.Fatalf("管理员读取任务期望 200，得到 %d: %s", status, body)
	}
	var pendingTask gen.DeviceApplyTask
	if err := json.Unmarshal(body, &pendingTask); err != nil || pendingTask.Status != gen.DeviceApplyStatusPending {
		t.Fatalf("越权领取改变了任务状态: %+v err=%v", pendingTask.Status, err)
	}
	// AI 不能登记发往未绑定设备的结果包。
	foreignForm, foreignContentType := deviceApplyForm(t, deviceApplyManifest("phone-b", master, nil), master, nil)
	if status, body := env.request(t, http.MethodPost, "/api/v1/device/apply-tasks", phoneAToken, foreignForm, foreignContentType, nil); status != http.StatusForbidden {
		t.Fatalf("AI 登记未绑定设备任务期望 403，得到 %d: %s", status, body)
	}
	// 未绑定任何设备的 AI 客户端不能使用设备端点。
	_, noDeviceToken := env.createClient(t, "no-device", []aiauth.Scope{aiauth.ScopeDeviceApply}, nil)
	if status, body := env.request(t, http.MethodGet, "/api/v1/device/apply-tasks?targetDevice=phone-a", noDeviceToken, nil, "", nil); status != http.StatusForbidden {
		t.Fatalf("未绑定设备客户端期望 403，得到 %d: %s", status, body)
	}
	// 管理员不受设备级限制。
	if status, body := env.request(t, http.MethodGet, "/api/v1/device/apply-tasks?targetDevice=phone-b", env.adminToken, nil, "", nil); status != http.StatusOK {
		t.Fatalf("管理员查询 phone-b 期望 200，得到 %d: %s", status, body)
	}
}

func TestAIClientDeviceApplyFlowAudit(t *testing.T) {
	env := newAIHTTPEnv(t)
	client, token := env.createClient(t, "phone-main-executor",
		[]aiauth.Scope{aiauth.ScopeDeviceApply}, []string{"phone-main"})
	master := deviceApplyMaster(32)
	form, contentType := deviceApplyForm(t, deviceApplyManifest("phone-main", master, nil), master, nil)
	status, body := env.request(t, http.MethodPost, "/api/v1/device/apply-tasks", token, form, contentType, nil)
	if status != http.StatusCreated {
		t.Fatalf("AI 登记自己设备结果包期望 201，得到 %d: %s", status, body)
	}
	var task gen.DeviceApplyTask
	if err := json.Unmarshal(body, &task); err != nil {
		t.Fatalf("解析设备任务失败: %v", err)
	}
	if task.TargetDeviceId != "phone-main" {
		t.Fatalf("目标设备异常: %s", task.TargetDeviceId)
	}
	taskPath := "/api/v1/device/apply-tasks/" + task.Id.String()
	if status, body := env.request(t, http.MethodPost, taskPath+"/claim", token, nil, "", nil); status != http.StatusOK {
		t.Fatalf("AI 领取自己设备任务期望 200，得到 %d: %s", status, body)
	}
	if status, body := env.request(t, http.MethodPost, taskPath+"/result", token,
		strings.NewReader("{\"status\":\"applied\"}"), "application/json", nil); status != http.StatusOK {
		t.Fatalf("AI 回传自己设备结果期望 200，得到 %d: %s", status, body)
	}

	rows := env.audits(t, client.ID)
	if len(rows) != 3 {
		t.Fatalf("设备操作审计条数 = %d，期望 3: %+v", len(rows), rows)
	}
	// 审计按新→旧返回：result、claim、create。
	expected := []struct {
		action     string
		statusCode int64
	}{
		{"device.apply:result:applied", int64(http.StatusOK)},
		{"device.apply:claim:phone-main", int64(http.StatusOK)},
		{"device.apply:create:phone-main", int64(http.StatusCreated)},
	}
	for i, want := range expected {
		if rows[i].Action != want.action || rows[i].StatusCode != want.statusCode {
			t.Fatalf("第 %d 条审计 = %+v，期望 action=%s status=%d", i, rows[i], want.action, want.statusCode)
		}
		if !strings.Contains(rows[i].Path, "/api/v1/device/apply-tasks") {
			t.Fatalf("审计 path 异常: %s", rows[i].Path)
		}
	}
}
