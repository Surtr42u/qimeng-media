// maintenance_executor.go：外部 AI 执行器的维护任务受控领取通道（ADR-0058）。
//
// 与内部 worker 共用同一账本与租约原语：领取由服务端生成 fencing token，
// 续租/步骤/工件/终态都必须携带该 token；暂停、取消、终态或租约过期后旧
// token 立即失效。协议见 api/openapi.yaml 的 /maintenance/executor 路径，
// 权限由 maintenance.execute scope 强制。
package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/maintenance"
)

// PostApiV1MaintenanceExecutorClaim 领取一个可执行任务；无可领取任务 204。
func (s *Server) PostApiV1MaintenanceExecutorClaim(w http.ResponseWriter, r *http.Request) {
	var body gen.PostApiV1MaintenanceExecutorClaimJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	kind := maintenance.Kind(body.Kind)
	if !kind.Valid() {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "kind 不在支持范围内")
		return
	}
	var ttl time.Duration
	if body.LeaseSeconds != nil {
		if *body.LeaseSeconds <= 0 {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "leaseSeconds 须为正整数")
			return
		}
		ttl = time.Duration(*body.LeaseSeconds) * time.Second
	}
	lease, ok, err := s.maintenance.ClaimForExecutor(r.Context(), kind, ttl)
	if err != nil {
		s.writeMaintenanceError(w, "领取维护任务", err)
		return
	}
	if !ok {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	writeJSON(w, http.StatusOK, gen.MaintenanceLease{
		Job:            maintenanceDetailToGen(lease.Detail),
		LeaseToken:     lease.LeaseToken,
		LeaseUntil:     lease.LeaseUntil,
		CompletedSteps: lease.CompletedSteps,
	})
}

// PostApiV1MaintenanceExecutorJobsIdHeartbeat 续租；租约失效 409 LEASE_LOST。
func (s *Server) PostApiV1MaintenanceExecutorJobsIdHeartbeat(w http.ResponseWriter, r *http.Request, id gen.MaintenanceJobId) {
	var body gen.PostApiV1MaintenanceExecutorJobsIdHeartbeatJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.LeaseToken) == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "leaseToken 不能为空")
		return
	}
	var ttl time.Duration
	if body.LeaseSeconds != nil {
		if *body.LeaseSeconds <= 0 {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "leaseSeconds 须为正整数")
			return
		}
		ttl = time.Duration(*body.LeaseSeconds) * time.Second
	}
	until, err := s.maintenance.HeartbeatForExecutor(r.Context(), id.String(), body.LeaseToken, ttl)
	if err != nil {
		s.writeMaintenanceError(w, "续租维护任务", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.MaintenanceLeaseStatus{
		JobId:      maintenanceUUID(id.String()),
		LeaseUntil: until,
	})
}

// PostApiV1MaintenanceExecutorJobsIdSteps 登记步骤（幂等 upsert，租约围栏）。
func (s *Server) PostApiV1MaintenanceExecutorJobsIdSteps(w http.ResponseWriter, r *http.Request, id gen.MaintenanceJobId) {
	var body gen.PostApiV1MaintenanceExecutorJobsIdStepsJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.LeaseToken) == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "leaseToken 不能为空")
		return
	}
	status := maintenance.StepStatus(body.Status)
	if !status.Valid() {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "status 不在支持范围内")
		return
	}
	detail := json.RawMessage(`{}`)
	if body.Detail != nil {
		encoded, err := json.Marshal(*body.Detail)
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "detail 不是合法 JSON 对象")
			return
		}
		if len(encoded) > maintenance.MaxStepDetailBytes {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "detail 超过 16KiB")
			return
		}
		detail = encoded
	}
	attempt := 0
	if body.Attempt != nil {
		attempt = *body.Attempt
	}
	if err := s.maintenance.RecordExecutorStep(r.Context(), maintenance.StepInput{
		JobID:      id.String(),
		Step:       body.Step,
		Status:     status,
		Attempt:    attempt,
		Detail:     detail,
		LeaseOwner: body.LeaseToken,
	}); err != nil {
		s.writeMaintenanceError(w, "登记维护步骤", err)
		return
	}
	detailView, err := s.maintenance.Get(r.Context(), id.String())
	if err != nil {
		s.writeMaintenanceError(w, "读取维护任务", err)
		return
	}
	writeJSON(w, http.StatusOK, maintenanceDetailToGen(detailView))
}

// PostApiV1MaintenanceExecutorJobsIdArtifacts 登记工件元数据（正文不入库）。
func (s *Server) PostApiV1MaintenanceExecutorJobsIdArtifacts(w http.ResponseWriter, r *http.Request, id gen.MaintenanceJobId) {
	var body gen.PostApiV1MaintenanceExecutorJobsIdArtifactsJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.LeaseToken) == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "leaseToken 不能为空")
		return
	}
	if err := s.maintenance.AddArtifact(r.Context(), maintenance.ArtifactInput{
		JobID:      id.String(),
		Kind:       body.Kind,
		URI:        body.Uri,
		SHA256:     body.Sha256,
		Bytes:      body.Bytes,
		LeaseOwner: body.LeaseToken,
	}); err != nil {
		s.writeMaintenanceError(w, "登记维护工件", err)
		return
	}
	detailView, err := s.maintenance.Get(r.Context(), id.String())
	if err != nil {
		s.writeMaintenanceError(w, "读取维护任务", err)
		return
	}
	writeJSON(w, http.StatusOK, maintenanceDetailToGen(detailView))
}

// PostApiV1MaintenanceExecutorJobsIdComplete 回传终态（租约围栏）。
func (s *Server) PostApiV1MaintenanceExecutorJobsIdComplete(w http.ResponseWriter, r *http.Request, id gen.MaintenanceJobId) {
	var body gen.PostApiV1MaintenanceExecutorJobsIdCompleteJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.LeaseToken) == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "leaseToken 不能为空")
		return
	}
	status := maintenance.Status(body.Status)
	switch status {
	case maintenance.StatusCompleted, maintenance.StatusPartial, maintenance.StatusFailed:
	default:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "status 须为 completed/partial/failed")
		return
	}
	errorCode, errorMessage := "", ""
	if body.ErrorCode != nil {
		errorCode = *body.ErrorCode
	}
	if body.ErrorMessage != nil {
		errorMessage = *body.ErrorMessage
	}
	detailView, err := s.maintenance.Complete(r.Context(), id.String(), body.LeaseToken, status, errorCode, errorMessage)
	if err != nil {
		s.writeMaintenanceError(w, "回传维护任务终态", err)
		return
	}
	writeJSON(w, http.StatusOK, maintenanceDetailToGen(detailView))
}
