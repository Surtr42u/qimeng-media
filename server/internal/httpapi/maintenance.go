// maintenance.go：持久维护任务账本端点（ADR-0054，P3 第一刀）。
//
// 分层（ADR-0019）：本文件只做协议校验、错误映射与响应装配；任务账本、
// 状态机、幂等、租约与步骤/工件索引都在 internal/maintenance。worker 的
// Claim/Heartbeat/Complete 与步骤/工件写入本轮不出 HTTP API，仅由后续
// worker 调用业务包内部原语。
//
// 错误映射：不存在 404 NOT_FOUND；非法状态迁移（含终态不可变更）409
// STATE_CONFLICT；参数/游标错误 400 INVALID_PARAM / INVALID_CURSOR。
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/maintenance"
)

// PostApiV1MaintenanceJobs 创建任务（幂等）：首次 201，同幂等键重放 200。
func (s *Server) PostApiV1MaintenanceJobs(w http.ResponseWriter, r *http.Request) {
	var body gen.PostApiV1MaintenanceJobsJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if !body.Kind.Valid() {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "kind 不在支持范围内")
		return
	}
	key := strings.TrimSpace(body.IdempotencyKey)
	if key == "" || len([]rune(key)) > maintenance.MaxIdempotencyKeyRunes {
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			"idempotencyKey 须为 1–200 字符")
		return
	}
	var libraryID string
	if body.LibraryId != nil {
		libraryID = strings.TrimSpace(*body.LibraryId)
	}
	var rulesVersion string
	if body.RulesVersion != nil {
		rulesVersion = strings.TrimSpace(*body.RulesVersion)
	}
	payload, err := json.Marshal(body.Payload)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "payload 不是合法 JSON 对象")
		return
	}
	if body.Payload == nil {
		payload = nil
	}
	budget, err := json.Marshal(body.Budget)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "budget 不是合法 JSON 对象")
		return
	}
	if body.Budget == nil {
		budget = nil
	}
	detail, created, err := s.maintenance.Create(r.Context(), maintenance.CreateInput{
		Kind:           maintenance.Kind(body.Kind),
		LibraryID:      libraryID,
		Payload:        payload,
		IdempotencyKey: key,
		RulesVersion:   rulesVersion,
		Budget:         budget,
	})
	if err != nil {
		s.writeMaintenanceError(w, "创建维护任务", err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, maintenanceDetailToGen(detail))
}

// GetApiV1MaintenanceJobs 列表：状态过滤 + 新建倒序 keyset 分页。
func (s *Server) GetApiV1MaintenanceJobs(w http.ResponseWriter, r *http.Request, params gen.GetApiV1MaintenanceJobsParams) {
	opts := maintenance.ListOptions{}
	if params.Status != nil {
		opts.Status = maintenance.Status(*params.Status)
	}
	if params.Limit != nil {
		if *params.Limit < 1 || *params.Limit > maintenance.MaxListLimit {
			writeErr(w, http.StatusBadRequest, codeInvalidParam,
				"limit 取值范围 1..100")
			return
		}
		opts.Limit = *params.Limit
	}
	if params.Cursor != nil {
		opts.Cursor = *params.Cursor
	}
	result, err := s.maintenance.List(r.Context(), opts)
	if err != nil {
		s.writeMaintenanceError(w, "列出维护任务", err)
		return
	}
	items := make([]gen.MaintenanceJobSummary, 0, len(result.Items))
	for i := range result.Items {
		items = append(items, maintenanceSummaryToGen(result.Items[i]))
	}
	var next *string
	if result.NextCursor != "" {
		value := result.NextCursor
		next = &value
	}
	writeJSON(w, http.StatusOK, gen.MaintenanceJobList{Items: items, NextCursor: next})
}

// GetApiV1MaintenanceJobsId 详情：账本字段 + 最近步骤 + 工件元数据。
func (s *Server) GetApiV1MaintenanceJobsId(w http.ResponseWriter, r *http.Request, id gen.MaintenanceJobId) {
	detail, err := s.maintenance.Get(r.Context(), id.String())
	if err != nil {
		s.writeMaintenanceError(w, "读取维护任务", err)
		return
	}
	writeJSON(w, http.StatusOK, maintenanceDetailToGen(detail))
}

// PostApiV1MaintenanceJobsIdPause 暂停：非终态 -> paused。
func (s *Server) PostApiV1MaintenanceJobsIdPause(w http.ResponseWriter, r *http.Request, id gen.MaintenanceJobId) {
	detail, err := s.maintenance.Pause(r.Context(), id.String())
	if err != nil {
		s.writeMaintenanceError(w, "暂停维护任务", err)
		return
	}
	writeJSON(w, http.StatusOK, maintenanceDetailToGen(detail))
}

// PostApiV1MaintenanceJobsIdResume 恢复：paused -> queued（清空错误与租约）。
func (s *Server) PostApiV1MaintenanceJobsIdResume(w http.ResponseWriter, r *http.Request, id gen.MaintenanceJobId) {
	detail, err := s.maintenance.Resume(r.Context(), id.String())
	if err != nil {
		s.writeMaintenanceError(w, "恢复维护任务", err)
		return
	}
	writeJSON(w, http.StatusOK, maintenanceDetailToGen(detail))
}

// PostApiV1MaintenanceJobsIdCancel 取消：任何非终态 -> cancelled。
func (s *Server) PostApiV1MaintenanceJobsIdCancel(w http.ResponseWriter, r *http.Request, id gen.MaintenanceJobId) {
	detail, err := s.maintenance.Cancel(r.Context(), id.String())
	if err != nil {
		s.writeMaintenanceError(w, "取消维护任务", err)
		return
	}
	writeJSON(w, http.StatusOK, maintenanceDetailToGen(detail))
}

// writeMaintenanceError 把账本哨兵映射为协议错误码。
func (s *Server) writeMaintenanceError(w http.ResponseWriter, what string, err error) {
	switch {
	case errors.Is(err, maintenance.ErrNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "维护任务不存在")
	case errors.Is(err, maintenance.ErrLeaseLost):
		writeErr(w, http.StatusConflict, codeLeaseLost, "租约已失效：任务被暂停/取消/终态或已重新领取")
	case errors.Is(err, maintenance.ErrStateConflict):
		writeErr(w, http.StatusConflict, codeStateConflict, "维护任务当前状态不允许该操作")
	case errors.Is(err, maintenance.ErrInvalidCursor):
		writeErr(w, http.StatusBadRequest, codeInvalidCursor, "分页游标不合法")
	case errors.Is(err, maintenance.ErrInvalidInput):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "维护任务参数不合法")
	default:
		s.internalErr(w, what, err)
	}
}

// maintenanceDetailToGen 装配详情响应（空步骤/工件保持 []，payload/budget
// 反序列化为对象；错误仅在 code/message 非空时出现）。
func maintenanceDetailToGen(detail maintenance.Detail) gen.MaintenanceJob {
	job := detail.Job
	out := gen.MaintenanceJob{
		Id:         maintenanceUUID(job.ID),
		Kind:       gen.MaintenanceJobKind(job.Kind),
		Status:     gen.MaintenanceJobStatus(job.Status),
		CreatedAt:  job.CreatedAt,
		UpdatedAt:  job.UpdatedAt,
		StartedAt:  job.StartedAt,
		FinishedAt: job.FinishedAt,
		StepCount:  job.StepCount,
		Progress:   job.Progress,
	}
	if job.LibraryID != "" {
		value := job.LibraryID
		out.LibraryId = &value
	}
	if job.RulesVersion != "" {
		value := job.RulesVersion
		out.RulesVersion = &value
	}
	if len(job.Payload) > 0 {
		var payload map[string]interface{}
		if json.Unmarshal(job.Payload, &payload) == nil {
			out.Payload = &payload
		}
	}
	if len(job.Budget) > 0 {
		var budget map[string]interface{}
		if json.Unmarshal(job.Budget, &budget) == nil {
			out.Budget = &budget
		}
	}
	steps := make([]gen.MaintenanceStep, 0, len(detail.Steps))
	for i := range detail.Steps {
		step := detail.Steps[i]
		item := gen.MaintenanceStep{
			Step:       step.Step,
			Status:     gen.MaintenanceStepStatus(step.Status),
			Attempt:    step.Attempt,
			StartedAt:  step.StartedAt,
			FinishedAt: step.FinishedAt,
			Detail:     map[string]interface{}{},
		}
		if len(step.Detail) > 0 {
			_ = json.Unmarshal(step.Detail, &item.Detail)
		}
		steps = append(steps, item)
	}
	out.Steps = &steps
	artifacts := make([]gen.MaintenanceArtifact, 0, len(detail.Artifacts))
	for i := range detail.Artifacts {
		artifact := detail.Artifacts[i]
		artifacts = append(artifacts, gen.MaintenanceArtifact{
			Kind:      artifact.Kind,
			Uri:       artifact.URI,
			Sha256:    artifact.SHA256,
			Bytes:     artifact.Bytes,
			CreatedAt: artifact.CreatedAt,
		})
	}
	out.Artifacts = &artifacts
	if job.ErrorCode != "" || job.ErrorMessage != "" {
		out.Error = &gen.Error{Code: job.ErrorCode, Message: job.ErrorMessage}
	}
	return out
}

// maintenanceSummaryToGen 装配列表摘要（不含 payload/steps/artifacts）。
func maintenanceSummaryToGen(job maintenance.Job) gen.MaintenanceJobSummary {
	return gen.MaintenanceJobSummary{
		Id:         maintenanceUUID(job.ID),
		Kind:       gen.MaintenanceJobKind(job.Kind),
		Status:     gen.MaintenanceJobStatus(job.Status),
		CreatedAt:  job.CreatedAt,
		UpdatedAt:  job.UpdatedAt,
		FinishedAt: job.FinishedAt,
		StepCount:  job.StepCount,
		Progress:   job.Progress,
	}
}

// maintenanceUUID 把账本字符串 ID 转成协议 UUID；库内 ID 由服务端生成，
// 非法值只可能来自外部直接改库，按零值输出而不是 panic。
func maintenanceUUID(value string) gen.MaintenanceJobId {
	id, err := uuid.Parse(value)
	if err != nil {
		return gen.MaintenanceJobId{}
	}
	return id
}
