package store

import (
	"context"
	"database/sql"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store/db"
)

// TestMaintenanceLedgerQueries 用真实 migration 与临时 SQLite 锁定
// maintenance_jobs/steps/artifacts 的查询合同：唯一幂等键、状态过滤、
// 步骤计数、步骤/工件索引与暂停迁移。
func TestMaintenanceLedgerQueries(t *testing.T) {
	_, q := openTestDB(t)
	ctx := context.Background()
	now := FormatTimestamp(time.Date(2026, 11, 7, 2, 0, 0, 0, time.UTC))
	jobID := uuid.NewString()
	params := db.CreateMaintenanceJobParams{
		ID:                 jobID,
		Kind:               "asset_research",
		Status:             "queued",
		LibraryID:          sql.NullString{String: "lib-test", Valid: true},
		PayloadJson:        `{"query":"synthetic"}`,
		InputFingerprint:   "fingerprint-1",
		RulesVersion:       "2026-11-07.1",
		AuthorizationScope: "",
		BudgetJson:         `{"maxSteps":5}`,
		IdempotencyKey:     "store-idem-1",
		CreatedAt:          now,
		UpdatedAt:          now,
	}
	if err := q.CreateMaintenanceJob(ctx, params); err != nil {
		t.Fatalf("插入维护任务失败: %v", err)
	}
	job, err := q.GetMaintenanceJob(ctx, jobID)
	if err != nil {
		t.Fatalf("读取维护任务失败: %v", err)
	}
	if job.Kind != "asset_research" || job.Status != "queued" || !job.LibraryID.Valid {
		t.Fatalf("任务字段异常: %+v", job)
	}
	byKey, err := q.GetMaintenanceJobByIdempotencyKey(ctx, "store-idem-1")
	if err != nil || byKey.ID != jobID {
		t.Fatalf("按幂等键读取失败: id=%s err=%v", byKey.ID, err)
	}

	// 幂等键永久唯一：即使换任务 ID 也不能复用。
	duplicate := params
	duplicate.ID = uuid.NewString()
	if err := q.CreateMaintenanceJob(ctx, duplicate); err == nil {
		t.Fatal("重复幂等键应被唯一索引拒绝")
	}

	// 状态过滤 + 步骤计数。
	if err := q.UpsertMaintenanceStep(ctx, db.UpsertMaintenanceStepParams{
		JobID: jobID, Step: "inventory", Status: "completed", Attempt: 1,
		StartedAt:  sql.NullString{String: now, Valid: true},
		FinishedAt: sql.NullString{String: now, Valid: true},
		DetailJson: `{"scanned":10}`,
	}); err != nil {
		t.Fatalf("写入步骤失败: %v", err)
	}
	if err := q.InsertMaintenanceArtifact(ctx, db.InsertMaintenanceArtifactParams{
		JobID: jobID, Kind: "report", Uri: "synthetic://report/1",
		Sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		Bytes:  12, CreatedAt: now,
	}); err != nil {
		t.Fatalf("写入工件失败: %v", err)
	}
	// 同 (job, kind, uri) 幂等。
	if err := q.InsertMaintenanceArtifact(ctx, db.InsertMaintenanceArtifactParams{
		JobID: jobID, Kind: "report", Uri: "synthetic://report/1",
		Sha256: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
		Bytes:  99, CreatedAt: now,
	}); err != nil {
		t.Fatalf("重复工件应幂等: %v", err)
	}
	counts, err := q.CountMaintenanceStepsByJob(ctx, jobID)
	if err != nil {
		t.Fatalf("统计步骤失败: %v", err)
	}
	if counts.StepCount != 1 || counts.CompletedSteps != 1 {
		t.Fatalf("步骤计数异常: %+v", counts)
	}
	steps, err := q.ListMaintenanceSteps(ctx, db.ListMaintenanceStepsParams{JobID: jobID, Limit: 10})
	if err != nil || len(steps) != 1 || steps[0].Status != "completed" {
		t.Fatalf("步骤列表异常: %+v err=%v", steps, err)
	}
	artifacts, err := q.ListMaintenanceArtifacts(ctx, db.ListMaintenanceArtifactsParams{JobID: jobID, Limit: 10})
	if err != nil || len(artifacts) != 1 || artifacts[0].Sha256[:1] != "a" {
		t.Fatalf("工件列表异常: %+v err=%v", artifacts, err)
	}

	rows, err := q.ListMaintenanceJobs(ctx, db.ListMaintenanceJobsParams{
		Status:   sql.NullString{String: "queued", Valid: true},
		RowLimit: 10,
	})
	if err != nil {
		t.Fatalf("列表查询失败: %v", err)
	}
	if len(rows) != 1 || rows[0].ID != jobID || rows[0].StepCount != 1 || rows[0].CompletedSteps != 1 {
		t.Fatalf("列表行异常: %+v", rows)
	}
	empty, err := q.ListMaintenanceJobs(ctx, db.ListMaintenanceJobsParams{
		Status:   sql.NullString{String: "paused", Valid: true},
		RowLimit: 10,
	})
	if err != nil || len(empty) != 0 {
		t.Fatalf("状态过滤异常: len=%d err=%v", len(empty), err)
	}

	affected, err := q.PauseMaintenanceJob(ctx, db.PauseMaintenanceJobParams{UpdatedAt: now, ID: jobID})
	if err != nil || affected != 1 {
		t.Fatalf("暂停迁移失败: affected=%d err=%v", affected, err)
	}
	paused, err := q.GetMaintenanceJob(ctx, jobID)
	if err != nil || paused.Status != "paused" || paused.LeaseOwner.Valid {
		t.Fatalf("暂停后字段异常: %+v err=%v", paused, err)
	}
}
