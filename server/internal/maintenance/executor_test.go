package maintenance

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"
)

// TestExecutorClaimLeaseFencingAndCompletedSteps 覆盖外部执行器通道的完整
// 生命周期：领取生成服务端 token、续租、步骤/工件围栏写入、终态与旧 token
// 失效，以及 completedSteps 不受详情 50 条窗口限制。
func TestExecutorClaimLeaseFencingAndCompletedSteps(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "executor-claim")

	lease, ok, err := svc.ClaimForExecutor(ctx, KindAssetResearch, 60*time.Second)
	if err != nil || !ok {
		t.Fatalf("领取失败: ok=%v err=%v", ok, err)
	}
	if !strings.HasPrefix(lease.LeaseToken, "executor/") {
		t.Fatalf("leaseToken = %q，期望服务端生成的 executor/ 前缀", lease.LeaseToken)
	}
	if !lease.LeaseUntil.Equal(clock.Now().Add(60 * time.Second)) {
		t.Fatalf("leaseUntil = %v，期望 %v", lease.LeaseUntil, clock.Now().Add(60*time.Second))
	}
	if len(lease.CompletedSteps) != 0 {
		t.Fatalf("新任务 completedSteps = %v，期望空", lease.CompletedSteps)
	}

	clock.Advance(20 * time.Second)
	until, err := svc.HeartbeatForExecutor(ctx, job.Job.ID, lease.LeaseToken, 120*time.Second)
	if err != nil {
		t.Fatalf("续租失败: %v", err)
	}
	if !until.Equal(clock.Now().Add(120 * time.Second)) {
		t.Fatalf("续租到期 = %v，期望 %v", until, clock.Now().Add(120*time.Second))
	}

	// 写入 60 个已完成步骤：详情端点只保留最近 50 条，completedSteps 必须
	// 返回全量（否则恢复执行器会重复副作用）。
	for i := 0; i < 60; i++ {
		if err := svc.RecordExecutorStep(ctx, StepInput{
			JobID:      job.Job.ID,
			Step:       fmt.Sprintf("step-%02d", i),
			Status:     StepCompleted,
			Attempt:    1,
			LeaseOwner: lease.LeaseToken,
		}); err != nil {
			t.Fatalf("登记步骤 %d 失败: %v", i, err)
		}
	}
	names, err := svc.CompletedStepNames(ctx, job.Job.ID)
	if err != nil {
		t.Fatalf("读取已完成步骤失败: %v", err)
	}
	if len(names) != 60 {
		t.Fatalf("completedSteps = %d，期望 60（详情窗口外也必须可见）", len(names))
	}
	detail, err := svc.Get(ctx, job.Job.ID)
	if err != nil {
		t.Fatalf("读取详情失败: %v", err)
	}
	if len(detail.Steps) != MaxDetailSteps {
		t.Fatalf("详情步骤 = %d，期望被截断为 %d", len(detail.Steps), MaxDetailSteps)
	}

	if err := svc.AddArtifact(ctx, ArtifactInput{
		JobID: job.Job.ID, Kind: "report", URI: "artifact://report",
		SHA256: strings.Repeat("a", 64), Bytes: 12, LeaseOwner: lease.LeaseToken,
	}); err != nil {
		t.Fatalf("登记工件失败: %v", err)
	}
	// 旧 token 在终态后不能续租，也不能再写步骤。
	done, err := svc.Complete(ctx, job.Job.ID, lease.LeaseToken, StatusCompleted, "", "")
	if err != nil {
		t.Fatalf("回传终态失败: %v", err)
	}
	if done.Job.Status != StatusCompleted || done.Job.FinishedAt == nil {
		t.Fatalf("终态异常: %+v", done.Job)
	}
	if _, err := svc.HeartbeatForExecutor(ctx, job.Job.ID, lease.LeaseToken, 0); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("终态后续租错误 = %v，期望 ErrLeaseLost", err)
	}
	if err := svc.RecordStep(ctx, StepInput{
		JobID: job.Job.ID, Step: "late-step", Status: StepCompleted, LeaseOwner: lease.LeaseToken,
	}); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("终态后写步骤错误 = %v，期望 ErrLeaseLost", err)
	}
	if _, ok, err := svc.ClaimForExecutor(ctx, KindAssetResearch, 0); err != nil || ok {
		t.Fatalf("终态任务不应再被领取: ok=%v err=%v", ok, err)
	}
}

// TestExecutorPauseInvalidatesLease 覆盖暂停清空租约后旧执行器立即失效。
func TestExecutorPauseInvalidatesLease(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "executor-pause")
	lease, ok, err := svc.ClaimForExecutor(ctx, KindAssetResearch, 0)
	if err != nil || !ok {
		t.Fatalf("领取失败: ok=%v err=%v", ok, err)
	}
	if _, err := svc.Pause(ctx, job.Job.ID); err != nil {
		t.Fatalf("暂停失败: %v", err)
	}
	if _, err := svc.HeartbeatForExecutor(ctx, job.Job.ID, lease.LeaseToken, 0); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("暂停后续租错误 = %v，期望 ErrLeaseLost", err)
	}
	if err := svc.RecordExecutorStep(ctx, StepInput{
		JobID: job.Job.ID, Step: "s1", Status: StepRunning, LeaseOwner: lease.LeaseToken,
	}); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("暂停后写步骤错误 = %v，期望 ErrLeaseLost", err)
	}
}

// TestRecordExecutorStepPreservesStartedAt 覆盖 running -> completed 的状态
// 合并：upsert 会覆盖全部列，完成时必须保留首次开始时刻。
func TestRecordExecutorStepPreservesStartedAt(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "executor-step-merge")
	lease, ok, err := svc.ClaimForExecutor(ctx, KindAssetResearch, 0)
	if err != nil || !ok {
		t.Fatalf("领取失败: ok=%v err=%v", ok, err)
	}
	started := clock.Now()
	if err := svc.RecordExecutorStep(ctx, StepInput{
		JobID: job.Job.ID, Step: "s1", Status: StepRunning, LeaseOwner: lease.LeaseToken,
	}); err != nil {
		t.Fatalf("登记 running 失败: %v", err)
	}
	clock.Advance(10 * time.Second)
	if err := svc.RecordExecutorStep(ctx, StepInput{
		JobID: job.Job.ID, Step: "s1", Status: StepCompleted, LeaseOwner: lease.LeaseToken,
	}); err != nil {
		t.Fatalf("登记 completed 失败: %v", err)
	}
	detail, err := svc.Get(ctx, job.Job.ID)
	if err != nil {
		t.Fatalf("读取详情失败: %v", err)
	}
	if len(detail.Steps) != 1 {
		t.Fatalf("步骤数 = %d，期望 1", len(detail.Steps))
	}
	step := detail.Steps[0]
	if step.StartedAt == nil || !step.StartedAt.Equal(started) {
		t.Fatalf("startedAt = %v，期望保留 %v", step.StartedAt, started)
	}
	if step.FinishedAt == nil || !step.FinishedAt.Equal(clock.Now()) {
		t.Fatalf("finishedAt = %v，期望 %v", step.FinishedAt, clock.Now())
	}
}

// TestNormalizeExecutorLeaseTTL 覆盖租约时长边界。
func TestNormalizeExecutorLeaseTTL(t *testing.T) {
	if ttl, err := NormalizeExecutorLeaseTTL(0); err != nil || ttl != DefaultExecutorLeaseTTL {
		t.Fatalf("缺省租约 = (%v,%v)", ttl, err)
	}
	if _, err := NormalizeExecutorLeaseTTL(MinExecutorLeaseTTL - time.Second); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("过短租约错误 = %v", err)
	}
	if _, err := NormalizeExecutorLeaseTTL(MaxExecutorLeaseTTL + time.Second); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("过长租约错误 = %v", err)
	}
}

// TestExecutorRejectsWorkerOwnedKind 覆盖外部通道的 kind 边界：backup_export
// 有 Go worker 内置 handler，外部执行器不得领取/关单。
func TestExecutorRejectsWorkerOwnedKind(t *testing.T) {
	svc, _ := newTestService(t)
	if _, _, err := svc.ClaimForExecutor(context.Background(), KindBackupExport, 0); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("领取 backup_export 错误 = %v，期望 ErrInvalidInput", err)
	}
}
