package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"qimeng-media/server/internal/aiauth"
	"qimeng-media/server/internal/httpapi/gen"
)

// TestMaintenanceExecutorHTTPFlow 覆盖外部执行器 HTTP 通道：领取返回
// fencing token、续租、步骤/工件登记、终态回传，以及无任务 204。
func TestMaintenanceExecutorHTTPFlow(t *testing.T) {
	env := newAIHTTPEnv(t)
	_, token := env.createClient(t, "executor", []aiauth.Scope{aiauth.ScopeMaintenanceExecute}, nil)

	status, body := env.request(t, http.MethodPost, "/api/v1/maintenance/jobs", env.adminToken,
		strings.NewReader(`{"kind":"custom","idempotencyKey":"exec-http-1"}`), "application/json", nil)
	if status != http.StatusCreated {
		t.Fatalf("创建任务 = %d %s", status, body)
	}
	var job gen.MaintenanceJob
	if err := json.Unmarshal(body, &job); err != nil {
		t.Fatalf("解析任务失败: %v", err)
	}

	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/claim", token,
		strings.NewReader(`{"kind":"custom","leaseSeconds":60}`), "application/json", nil)
	if status != http.StatusOK {
		t.Fatalf("领取 = %d %s", status, body)
	}
	var lease gen.MaintenanceLease
	if err := json.Unmarshal(body, &lease); err != nil {
		t.Fatalf("解析租约失败: %v", err)
	}
	if lease.LeaseToken == "" || lease.Job.Id != job.Id || len(lease.CompletedSteps) != 0 {
		t.Fatalf("租约异常: %+v", lease)
	}

	heartbeat := `{"leaseToken":"` + lease.LeaseToken + `","leaseSeconds":90}`
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/jobs/"+job.Id.String()+"/heartbeat", token,
		strings.NewReader(heartbeat), "application/json", nil)
	if status != http.StatusOK {
		t.Fatalf("续租 = %d %s", status, body)
	}
	var leaseStatus gen.MaintenanceLeaseStatus
	if err := json.Unmarshal(body, &leaseStatus); err != nil || leaseStatus.JobId != job.Id {
		t.Fatalf("续租响应异常: %+v err=%v", leaseStatus, err)
	}

	step := `{"leaseToken":"` + lease.LeaseToken + `","step":"prepare","status":"completed","detail":{"items":3}}`
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/jobs/"+job.Id.String()+"/steps", token,
		strings.NewReader(step), "application/json", nil)
	if status != http.StatusOK {
		t.Fatalf("登记步骤 = %d %s", status, body)
	}
	var afterStep gen.MaintenanceJob
	if err := json.Unmarshal(body, &afterStep); err != nil || afterStep.Steps == nil || len(*afterStep.Steps) != 1 || (*afterStep.Steps)[0].Step != "prepare" {
		t.Fatalf("步骤响应异常: %+v err=%v", afterStep, err)
	}

	artifact := `{"leaseToken":"` + lease.LeaseToken + `","kind":"report","uri":"artifact://report","sha256":"` +
		strings.Repeat("a", 64) + `","bytes":7}`
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/jobs/"+job.Id.String()+"/artifacts", token,
		strings.NewReader(artifact), "application/json", nil)
	if status != http.StatusOK {
		t.Fatalf("登记工件 = %d %s", status, body)
	}
	var afterArtifact gen.MaintenanceJob
	if err := json.Unmarshal(body, &afterArtifact); err != nil || afterArtifact.Artifacts == nil || len(*afterArtifact.Artifacts) != 1 {
		t.Fatalf("工件响应异常: %+v err=%v", afterArtifact, err)
	}

	complete := `{"leaseToken":"` + lease.LeaseToken + `","status":"completed"}`
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/jobs/"+job.Id.String()+"/complete", token,
		strings.NewReader(complete), "application/json", nil)
	if status != http.StatusOK {
		t.Fatalf("回传终态 = %d %s", status, body)
	}
	var finished gen.MaintenanceJob
	if err := json.Unmarshal(body, &finished); err != nil || finished.Status != gen.MaintenanceStatusCompleted {
		t.Fatalf("终态响应异常: %+v err=%v", finished, err)
	}

	// 终态后无可领取任务（204）；旧 token 续租 409 LEASE_LOST。
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/claim", token,
		strings.NewReader(`{"kind":"custom"}`), "application/json", nil)
	if status != http.StatusNoContent {
		t.Fatalf("无任务领取 = %d %s，期望 204", status, body)
	}
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/jobs/"+job.Id.String()+"/heartbeat", token,
		strings.NewReader(heartbeat), "application/json", nil)
	if status != http.StatusConflict || aiErrorCode(t, body) != codeLeaseLost {
		t.Fatalf("终态续租 = %d %s，期望 409 LEASE_LOST", status, body)
	}
}

// TestMaintenanceExecutorPauseInvalidatesLeaseHTTP 覆盖暂停后旧执行器立即
// 失去租约（跨请求的 fencing 语义）。
func TestMaintenanceExecutorPauseInvalidatesLeaseHTTP(t *testing.T) {
	env := newAIHTTPEnv(t)
	_, token := env.createClient(t, "executor", []aiauth.Scope{aiauth.ScopeMaintenanceExecute}, nil)
	status, body := env.request(t, http.MethodPost, "/api/v1/maintenance/jobs", env.adminToken,
		strings.NewReader(`{"kind":"custom","idempotencyKey":"exec-http-pause"}`), "application/json", nil)
	if status != http.StatusCreated {
		t.Fatalf("创建任务 = %d %s", status, body)
	}
	var job gen.MaintenanceJob
	if err := json.Unmarshal(body, &job); err != nil {
		t.Fatalf("解析任务失败: %v", err)
	}
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/claim", token,
		strings.NewReader(`{"kind":"custom"}`), "application/json", nil)
	if status != http.StatusOK {
		t.Fatalf("领取 = %d %s", status, body)
	}
	var lease gen.MaintenanceLease
	if err := json.Unmarshal(body, &lease); err != nil {
		t.Fatalf("解析租约失败: %v", err)
	}
	if status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/jobs/"+job.Id.String()+"/pause", env.adminToken, nil, "", nil); status != http.StatusOK {
		t.Fatalf("暂停 = %d %s", status, body)
	}
	status, body = env.request(t, http.MethodPost, "/api/v1/maintenance/executor/jobs/"+job.Id.String()+"/heartbeat", token,
		strings.NewReader(`{"leaseToken":"`+lease.LeaseToken+`"}`), "application/json", nil)
	if status != http.StatusConflict || aiErrorCode(t, body) != codeLeaseLost {
		t.Fatalf("暂停后续租 = %d %s，期望 409 LEASE_LOST", status, body)
	}
}

// TestMaintenanceExecutorRequiresScope 覆盖 scope 默认拒绝：只读客户端不能
// 领取执行器任务。
func TestMaintenanceExecutorRequiresScope(t *testing.T) {
	env := newAIHTTPEnv(t)
	_, token := env.createClient(t, "reader", []aiauth.Scope{aiauth.ScopeRead}, nil)
	status, body := env.request(t, http.MethodPost, "/api/v1/maintenance/executor/claim", token,
		strings.NewReader(`{"kind":"custom"}`), "application/json", nil)
	if status != http.StatusForbidden || aiErrorCode(t, body) != codeForbiddenScope {
		t.Fatalf("缺少 maintenance.execute = %d %s，期望 403 FORBIDDEN_SCOPE", status, body)
	}
}
