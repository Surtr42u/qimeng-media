package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/maintenance"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func newMaintenanceHTTPTestServer(t *testing.T) *Server {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "maintenance-http-test.db"))
	if err != nil {
		t.Fatalf("打开临时库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移临时库失败: %v", err)
	}
	clock := func() time.Time { return time.Date(2026, 11, 7, 1, 0, 0, 0, time.UTC) }
	svc := maintenance.New(maintenance.Options{
		Conn: conn, Queries: db.New(conn), Now: clock,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	return &Server{
		maintenance: svc,
		logger:      slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
}

func maintenanceRequest(t *testing.T, s *Server, method, target string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("序列化请求失败: %v", err)
		}
		reader = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(method, target, reader)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	rec := httptest.NewRecorder()
	switch {
	case method == http.MethodPost && target == "/api/v1/maintenance/jobs":
		s.PostApiV1MaintenanceJobs(rec, req)
	default:
		t.Fatalf("未覆盖的请求: %s %s", method, target)
	}
	return rec
}

func createMaintenanceJobHTTP(t *testing.T, s *Server, body gen.CreateMaintenanceJobRequest) (int, gen.MaintenanceJob) {
	t.Helper()
	rec := maintenanceRequest(t, s, http.MethodPost, "/api/v1/maintenance/jobs", body)
	if rec.Code != http.StatusCreated && rec.Code != http.StatusOK {
		t.Fatalf("创建返回 %d: %s", rec.Code, rec.Body.String())
	}
	var job gen.MaintenanceJob
	if err := json.Unmarshal(rec.Body.Bytes(), &job); err != nil {
		t.Fatalf("解析创建响应失败: %v", err)
	}
	return rec.Code, job
}

func TestMaintenanceCreateAndReplayHTTP(t *testing.T) {
	s := newMaintenanceHTTPTestServer(t)
	payload := map[string]interface{}{"query": "synthetic", "limit": float64(3)}
	budget := map[string]interface{}{"maxSteps": float64(5)}
	body := gen.CreateMaintenanceJobRequest{
		Kind:           gen.MaintenanceKindAssetResearch,
		LibraryId:      ptr("lib-test"),
		Payload:        &payload,
		IdempotencyKey: "http-idem-1",
		RulesVersion:   ptr("2026-11-07.1"),
		Budget:         &budget,
	}
	status, first := createMaintenanceJobHTTP(t, s, body)
	if status != http.StatusCreated {
		t.Fatalf("首次创建状态 = %d，期望 201", status)
	}
	if first.Status != gen.MaintenanceStatusQueued || first.Kind != gen.MaintenanceKindAssetResearch {
		t.Fatalf("创建响应异常: %+v", first)
	}
	if first.StepCount != 0 || first.Progress != 0 {
		t.Fatalf("新任务进度异常: stepCount=%d progress=%d", first.StepCount, first.Progress)
	}
	if first.Payload == nil || (*first.Payload)["query"] != "synthetic" {
		t.Fatalf("payload 回显异常: %+v", first.Payload)
	}
	if first.LibraryId == nil || *first.LibraryId != "lib-test" {
		t.Fatalf("libraryId 回显异常: %+v", first.LibraryId)
	}

	status, replay := createMaintenanceJobHTTP(t, s, body)
	if status != http.StatusOK {
		t.Fatalf("幂等重放状态 = %d，期望 200", status)
	}
	if replay.Id != first.Id {
		t.Fatalf("幂等重放 ID 不一致: %s != %s", replay.Id, first.Id)
	}

	// 同幂等键不同载荷：载荷指纹不同 -> 409 STATE_CONFLICT，不得把旧任务
	// 当成成功重放返回（任务书 §8.3 冲突保护）。
	conflictPayload := map[string]interface{}{"query": "different"}
	conflictBody := body
	conflictBody.Payload = &conflictPayload
	conflictRec := maintenanceRequest(t, s, http.MethodPost, "/api/v1/maintenance/jobs", conflictBody)
	if conflictRec.Code != http.StatusConflict {
		t.Fatalf("同键不同载荷状态 = %d，期望 409: %s", conflictRec.Code, conflictRec.Body.String())
	}
	var conflict gen.Error
	if err := json.Unmarshal(conflictRec.Body.Bytes(), &conflict); err != nil || conflict.Code != codeStateConflict {
		t.Fatalf("同键不同载荷错误 = %+v err=%v", conflict, err)
	}

	badKind := body
	badKind.Kind = "bogus"
	rec := maintenanceRequest(t, s, http.MethodPost, "/api/v1/maintenance/jobs", badKind)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 kind 状态 = %d，期望 400", rec.Code)
	}
	var apiErr gen.Error
	if err := json.Unmarshal(rec.Body.Bytes(), &apiErr); err != nil || apiErr.Code != codeInvalidParam {
		t.Fatalf("非法 kind 错误 = %+v err=%v", apiErr, err)
	}

	emptyKey := body
	emptyKey.IdempotencyKey = " "
	rec = maintenanceRequest(t, s, http.MethodPost, "/api/v1/maintenance/jobs", emptyKey)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("空幂等键状态 = %d，期望 400", rec.Code)
	}
}

func TestMaintenanceListAndGetHTTP(t *testing.T) {
	s := newMaintenanceHTTPTestServer(t)
	firstStatus, first := createMaintenanceJobHTTP(t, s, gen.CreateMaintenanceJobRequest{
		Kind: gen.MaintenanceKindCustom, IdempotencyKey: "http-list-1",
	})
	if firstStatus != http.StatusCreated {
		t.Fatalf("创建第一笔失败: %d", firstStatus)
	}
	_, second := createMaintenanceJobHTTP(t, s, gen.CreateMaintenanceJobRequest{
		Kind: gen.MaintenanceKindBackupExport, IdempotencyKey: "http-list-2",
	})
	// 暂停第二笔：列表按状态过滤。
	pauseRec := httptest.NewRecorder()
	s.PostApiV1MaintenanceJobsIdPause(pauseRec, httptest.NewRequest(http.MethodPost, "/", nil), second.Id)
	if pauseRec.Code != http.StatusOK {
		t.Fatalf("暂停返回 %d: %s", pauseRec.Code, pauseRec.Body.String())
	}

	listRec := httptest.NewRecorder()
	s.GetApiV1MaintenanceJobs(listRec, httptest.NewRequest(http.MethodGet, "/", nil), gen.GetApiV1MaintenanceJobsParams{})
	if listRec.Code != http.StatusOK {
		t.Fatalf("列表返回 %d: %s", listRec.Code, listRec.Body.String())
	}
	var list gen.MaintenanceJobList
	if err := json.Unmarshal(listRec.Body.Bytes(), &list); err != nil {
		t.Fatalf("解析列表失败: %v", err)
	}
	if len(list.Items) != 2 {
		t.Fatalf("列表条数 = %d，期望 2", len(list.Items))
	}
	if list.NextCursor != nil {
		t.Fatalf("两笔任务不应有下一页: %v", *list.NextCursor)
	}

	pausedStatus := gen.MaintenanceStatusPaused
	filterRec := httptest.NewRecorder()
	s.GetApiV1MaintenanceJobs(filterRec, httptest.NewRequest(http.MethodGet, "/", nil), gen.GetApiV1MaintenanceJobsParams{Status: &pausedStatus})
	if filterRec.Code != http.StatusOK {
		t.Fatalf("状态过滤返回 %d: %s", filterRec.Code, filterRec.Body.String())
	}
	var filtered gen.MaintenanceJobList
	if err := json.Unmarshal(filterRec.Body.Bytes(), &filtered); err != nil {
		t.Fatalf("解析过滤列表失败: %v", err)
	}
	if len(filtered.Items) != 1 || filtered.Items[0].Id != second.Id {
		t.Fatalf("状态过滤异常: %+v", filtered.Items)
	}

	// 两笔任务共用同一 fake 时钟，排序由随机 UUID 的 id DESC 决定；
	// 不假设 first/second 的插入顺序，按 id 词序推导期望顺序。
	newer, older := first, second
	if older.Id.String() > newer.Id.String() {
		newer, older = older, newer
	}
	limitOne := 1
	pageRec := httptest.NewRecorder()
	s.GetApiV1MaintenanceJobs(pageRec, httptest.NewRequest(http.MethodGet, "/", nil), gen.GetApiV1MaintenanceJobsParams{Limit: &limitOne})
	if pageRec.Code != http.StatusOK {
		t.Fatalf("分页返回 %d: %s", pageRec.Code, pageRec.Body.String())
	}
	var page gen.MaintenanceJobList
	if err := json.Unmarshal(pageRec.Body.Bytes(), &page); err != nil {
		t.Fatalf("解析分页失败: %v", err)
	}
	if len(page.Items) == 1 && page.Items[0].Id != newer.Id {
		t.Fatalf("分页首页 id = %s，期望 %s", page.Items[0].Id, newer.Id)
	}
	if len(page.Items) != 1 || page.NextCursor == nil || *page.NextCursor == "" {
		t.Fatalf("分页首页异常: %+v", page)
	}
	nextRec := httptest.NewRecorder()
	s.GetApiV1MaintenanceJobs(nextRec, httptest.NewRequest(http.MethodGet, "/", nil), gen.GetApiV1MaintenanceJobsParams{Limit: &limitOne, Cursor: page.NextCursor})
	if nextRec.Code != http.StatusOK {
		t.Fatalf("第二页返回 %d: %s", nextRec.Code, nextRec.Body.String())
	}
	var secondPage gen.MaintenanceJobList
	if err := json.Unmarshal(nextRec.Body.Bytes(), &secondPage); err != nil {
		t.Fatalf("解析第二页失败: %v", err)
	}
	if len(secondPage.Items) != 1 || secondPage.Items[0].Id != older.Id {
		t.Fatalf("第二页异常: %+v", secondPage.Items)
	}

	detailRec := httptest.NewRecorder()
	s.GetApiV1MaintenanceJobsId(detailRec, httptest.NewRequest(http.MethodGet, "/", nil), first.Id)
	if detailRec.Code != http.StatusOK {
		t.Fatalf("详情返回 %d: %s", detailRec.Code, detailRec.Body.String())
	}
	var detail gen.MaintenanceJob
	if err := json.Unmarshal(detailRec.Body.Bytes(), &detail); err != nil {
		t.Fatalf("解析详情失败: %v", err)
	}
	if detail.Id != first.Id || detail.Steps == nil || len(*detail.Steps) != 0 {
		t.Fatalf("详情异常: %+v", detail)
	}

	zero := 0
	badLimitRec := httptest.NewRecorder()
	s.GetApiV1MaintenanceJobs(badLimitRec, httptest.NewRequest(http.MethodGet, "/", nil), gen.GetApiV1MaintenanceJobsParams{Limit: &zero})
	if badLimitRec.Code != http.StatusBadRequest {
		t.Fatalf("limit=0 状态 = %d，期望 400", badLimitRec.Code)
	}
	badCursor := "not-a-cursor"
	badCursorRec := httptest.NewRecorder()
	s.GetApiV1MaintenanceJobs(badCursorRec, httptest.NewRequest(http.MethodGet, "/", nil), gen.GetApiV1MaintenanceJobsParams{Cursor: &badCursor})
	if badCursorRec.Code != http.StatusBadRequest {
		t.Fatalf("非法游标状态 = %d，期望 400", badCursorRec.Code)
	}
	var cursorErr gen.Error
	if err := json.Unmarshal(badCursorRec.Body.Bytes(), &cursorErr); err != nil || cursorErr.Code != codeInvalidCursor {
		t.Fatalf("非法游标错误 = %+v err=%v", cursorErr, err)
	}
}

func TestMaintenanceStateErrorsHTTP(t *testing.T) {
	s := newMaintenanceHTTPTestServer(t)
	_, job := createMaintenanceJobHTTP(t, s, gen.CreateMaintenanceJobRequest{
		Kind: gen.MaintenanceKindAuthorCleanup, IdempotencyKey: "http-state-1",
	})

	pause := func() *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		s.PostApiV1MaintenanceJobsIdPause(rec, httptest.NewRequest(http.MethodPost, "/", nil), job.Id)
		return rec
	}
	resume := func() *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		s.PostApiV1MaintenanceJobsIdResume(rec, httptest.NewRequest(http.MethodPost, "/", nil), job.Id)
		return rec
	}
	cancel := func() *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		s.PostApiV1MaintenanceJobsIdCancel(rec, httptest.NewRequest(http.MethodPost, "/", nil), job.Id)
		return rec
	}

	if rec := pause(); rec.Code != http.StatusOK {
		t.Fatalf("暂停返回 %d: %s", rec.Code, rec.Body.String())
	}
	if rec := pause(); rec.Code != http.StatusConflict {
		t.Fatalf("重复暂停返回 %d，期望 409", rec.Code)
	}
	if rec := resume(); rec.Code != http.StatusOK {
		t.Fatalf("恢复返回 %d: %s", rec.Code, rec.Body.String())
	}
	if rec := resume(); rec.Code != http.StatusConflict {
		t.Fatalf("非 paused 恢复返回 %d，期望 409", rec.Code)
	}
	if rec := cancel(); rec.Code != http.StatusOK {
		t.Fatalf("取消返回 %d: %s", rec.Code, rec.Body.String())
	}
	terminalRec := cancel()
	if terminalRec.Code != http.StatusConflict {
		t.Fatalf("终态取消返回 %d，期望 409", terminalRec.Code)
	}
	var conflict gen.Error
	if err := json.Unmarshal(terminalRec.Body.Bytes(), &conflict); err != nil || conflict.Code != codeStateConflict {
		t.Fatalf("终态错误 = %+v err=%v", conflict, err)
	}

	missing := uuid.New()
	missingRec := httptest.NewRecorder()
	s.GetApiV1MaintenanceJobsId(missingRec, httptest.NewRequest(http.MethodGet, "/", nil), missing)
	if missingRec.Code != http.StatusNotFound {
		t.Fatalf("缺失任务详情返回 %d，期望 404", missingRec.Code)
	}
	var notFound gen.Error
	if err := json.Unmarshal(missingRec.Body.Bytes(), &notFound); err != nil || notFound.Code != codeNotFound {
		t.Fatalf("404 错误 = %+v err=%v", notFound, err)
	}
}

func TestMaintenanceHandlerValidationHTTP(t *testing.T) {
	s := newMaintenanceHTTPTestServer(t)
	body := gen.CreateMaintenanceJobRequest{
		Kind:           gen.MaintenanceKindCustom,
		IdempotencyKey: strings.Repeat("k", maintenance.MaxIdempotencyKeyRunes+1),
	}
	rec := maintenanceRequest(t, s, http.MethodPost, "/api/v1/maintenance/jobs", body)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("超长幂等键返回 %d，期望 400", rec.Code)
	}

	// 非 application/json：统一 INVALID_BODY（跨站简单请求防线）。
	raw, _ := json.Marshal(gen.CreateMaintenanceJobRequest{
		Kind: gen.MaintenanceKindCustom, IdempotencyKey: "content-type",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/maintenance/jobs", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "text/plain")
	rec = httptest.NewRecorder()
	s.PostApiV1MaintenanceJobs(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("text/plain 返回 %d，期望 400", rec.Code)
	}
	var apiErr gen.Error
	if err := json.Unmarshal(rec.Body.Bytes(), &apiErr); err != nil || apiErr.Code != codeInvalidBody {
		t.Fatalf("Content-Type 错误 = %+v err=%v", apiErr, err)
	}

	// 非法 JSON 体同样 400 INVALID_BODY。
	req = httptest.NewRequest(http.MethodPost, "/api/v1/maintenance/jobs", strings.NewReader("{"))
	req.Header.Set("Content-Type", "application/json")
	rec = httptest.NewRecorder()
	s.PostApiV1MaintenanceJobs(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 JSON 返回 %d，期望 400", rec.Code)
	}
}

func TestMaintenancePayloadLimitHTTP(t *testing.T) {
	s := newMaintenanceHTTPTestServer(t)
	payload := map[string]interface{}{"blob": strings.Repeat("x", maintenance.MaxPayloadBytes)}
	body := gen.CreateMaintenanceJobRequest{
		Kind: gen.MaintenanceKindCustom, IdempotencyKey: "payload-limit", Payload: &payload,
	}
	rec := maintenanceRequest(t, s, http.MethodPost, "/api/v1/maintenance/jobs", body)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("超大 payload 返回 %d，期望 400", rec.Code)
	}
	var apiErr gen.Error
	if err := json.Unmarshal(rec.Body.Bytes(), &apiErr); err != nil || apiErr.Code != codeInvalidParam {
		t.Fatalf("payload 错误 = %+v err=%v", apiErr, err)
	}
}

func TestMaintenanceTerminalImmutableHTTP(t *testing.T) {
	s := newMaintenanceHTTPTestServer(t)
	_, job := createMaintenanceJobHTTP(t, s, gen.CreateMaintenanceJobRequest{
		Kind: gen.MaintenanceKindCustom, IdempotencyKey: "http-terminal-1",
	})
	// 直接经业务包取消，验证 HTTP 侧终态映射（worker 不在本切片）。
	if _, err := s.maintenance.Cancel(context.Background(), job.Id.String()); err != nil {
		t.Fatalf("取消失败: %v", err)
	}
	for name, call := range map[string]func(*httptest.ResponseRecorder){
		"pause": func(rec *httptest.ResponseRecorder) {
			s.PostApiV1MaintenanceJobsIdPause(rec, httptest.NewRequest(http.MethodPost, "/", nil), job.Id)
		},
		"resume": func(rec *httptest.ResponseRecorder) {
			s.PostApiV1MaintenanceJobsIdResume(rec, httptest.NewRequest(http.MethodPost, "/", nil), job.Id)
		},
		"cancel": func(rec *httptest.ResponseRecorder) {
			s.PostApiV1MaintenanceJobsIdCancel(rec, httptest.NewRequest(http.MethodPost, "/", nil), job.Id)
		},
	} {
		rec := httptest.NewRecorder()
		call(rec)
		if rec.Code != http.StatusConflict {
			t.Fatalf("%s 终态返回 %d，期望 409", name, rec.Code)
		}
	}
}
