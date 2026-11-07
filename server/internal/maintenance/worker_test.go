package maintenance

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"qimeng-media/server/internal/backup"
)

// workerTestSteps 是最小 3 步流水线（inventory -> applying -> verifying），
// 覆盖状态推进与完成前 verifying 收敛。
var workerTestSteps = []StepSpec{
	{Name: "inventory", Stage: StatusInventory},
	{Name: "apply", Stage: StatusApplying},
	{Name: "verify", Stage: StatusVerifying},
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// recordingHandler 是可注入行为的合成 handler：记录每步执行次数，hook 可
// 阻塞/失败/暂停任务，用于确定性复现 worker 的可靠性路径。
type recordingHandler struct {
	steps  []StepSpec
	hook   func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error)
	mu     sync.Mutex
	counts map[string]int
}

func newRecordingHandler(steps []StepSpec) *recordingHandler {
	return &recordingHandler{steps: steps, counts: make(map[string]int)}
}

func (h *recordingHandler) Steps(Job) []StepSpec { return h.steps }

func (h *recordingHandler) Execute(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
	h.mu.Lock()
	h.counts[spec.Name]++
	hook := h.hook
	h.mu.Unlock()
	if hook != nil {
		return hook(ctx, job, spec, prior)
	}
	return StepOutcome{}, nil
}

func (h *recordingHandler) count(step string) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.counts[step]
}

func newTestWorker(t *testing.T, svc *Service, clock *testClock, handler Handler, kind Kind, owner string, ttl time.Duration) *Worker {
	t.Helper()
	return NewWorker(WorkerOptions{
		Service: svc, Logger: discardLogger(), Owner: owner,
		LeaseTTL: ttl, PollInterval: 10 * time.Millisecond, Now: clock.Now,
		Handlers: map[Kind]Handler{kind: handler},
	})
}

func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("等待超时: %s", what)
}

func getDetail(t *testing.T, svc *Service, id string) Detail {
	t.Helper()
	detail, err := svc.Get(context.Background(), id)
	if err != nil {
		t.Fatalf("回读任务 %s 失败: %v", id, err)
	}
	return detail
}

func findStep(detail Detail, name string) (Step, bool) {
	for _, step := range detail.Steps {
		if step.Step == name {
			return step, true
		}
	}
	return Step{}, false
}

func mustCreateKind(t *testing.T, svc *Service, kind Kind, key string) Detail {
	t.Helper()
	detail, created, err := svc.Create(context.Background(), CreateInput{
		Kind: kind, IdempotencyKey: key, Payload: json.RawMessage(`{"synthetic":true}`),
	})
	if err != nil || !created {
		t.Fatalf("创建 %s 任务失败: created=%v err=%v", kind, created, err)
	}
	return detail
}

// TestWorkerClaimsExecutesAndHeartbeats 覆盖：按 kind 领取最旧 queued 任务、
// 执行步骤、心跳续租（fake 时钟推进后 lease_until 前移）、完成终态。
func TestWorkerClaimsExecutesAndHeartbeats(t *testing.T) {
	svc, clock := newTestService(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	job := mustCreateKind(t, svc, KindAssetResearch, "worker-heartbeat")

	entered := make(chan struct{})
	release := make(chan struct{})
	var enteredOnce sync.Once
	handler := newRecordingHandler(workerTestSteps)
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if spec.Name == "apply" {
			enteredOnce.Do(func() { close(entered) })
			<-release
		}
		return StepOutcome{}, nil
	}
	worker := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-heartbeat", 90*time.Millisecond)

	done := make(chan struct{})
	go func() {
		defer close(done)
		worker.claimAndProcessOne(ctx)
	}()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("worker 未在期限内开始执行步骤")
	}
	claimed := getDetail(t, svc, job.Job.ID)
	if !strings.HasPrefix(claimed.Job.LeaseOwner, "worker-heartbeat/") || claimed.Job.LeaseUntil == nil {
		t.Fatalf("领取后租约异常: owner=%q until=%v", claimed.Job.LeaseOwner, claimed.Job.LeaseUntil)
	}
	before := *claimed.Job.LeaseUntil

	// 在租约未过期窗口内推进 fake 时钟：心跳应把 lease_until 续到新时钟 +
	// TTL（过期租约不可续租，见 TestFencedWritesRequireUnexpiredLease）。
	clock.Advance(30 * time.Millisecond)
	waitFor(t, 3*time.Second, "心跳续租", func() bool {
		current := getDetail(t, svc, job.Job.ID)
		return current.Job.LeaseUntil != nil && current.Job.LeaseUntil.After(before)
	})
	close(release)
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("worker 未在期限内完成领取处理")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusCompleted || final.Job.FinishedAt == nil {
		t.Fatalf("任务终态异常: status=%s finishedAt=%v", final.Job.Status, final.Job.FinishedAt)
	}
	if final.Job.StepCount != len(workerTestSteps) {
		t.Fatalf("步骤数 = %d，期望 %d", final.Job.StepCount, len(workerTestSteps))
	}
	for _, step := range workerTestSteps {
		recorded, ok := findStep(final, step.Name)
		if !ok || recorded.Status != StepCompleted {
			t.Fatalf("步骤 %q 未完成: %+v", step.Name, recorded)
		}
		if handler.count(step.Name) != 1 {
			t.Fatalf("步骤 %q 执行次数 = %d，期望 1", step.Name, handler.count(step.Name))
		}
	}
}

// TestWorkerResumesAfterLeaseExpiry 覆盖：进程崩溃（ctx 取消、无终态）后租约
// 过期，另一持有者接管，从已完成步骤续跑且不重复 inventory 的副作用。
func TestWorkerResumesAfterLeaseExpiry(t *testing.T) {
	svc, clock := newTestService(t)
	job := mustCreateKind(t, svc, KindAssetResearch, "worker-resume")

	var firstApply atomic.Bool
	firstApply.Store(true)
	applyEntered := make(chan struct{})
	var enteredOnce sync.Once
	handler := newRecordingHandler(workerTestSteps)
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if spec.Name == "apply" && firstApply.CompareAndSwap(true, false) {
			enteredOnce.Do(func() { close(applyEntered) })
			<-ctx.Done()
			return StepOutcome{}, ctx.Err()
		}
		return StepOutcome{}, nil
	}

	workerA := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-a", time.Second)
	ctxA, cancelA := context.WithCancel(context.Background())
	claimedA, ok, err := svc.Claim(ctxA, KindAssetResearch, workerA.newLeaseToken(), time.Second)
	if err != nil || !ok {
		t.Fatalf("worker-a 领取失败: ok=%v err=%v", ok, err)
	}
	doneA := make(chan struct{})
	go func() {
		defer close(doneA)
		workerA.process(ctxA, claimedA, claimedA.Job.LeaseOwner)
	}()
	select {
	case <-applyEntered:
	case <-time.After(3 * time.Second):
		t.Fatal("worker-a 未进入 apply 步骤")
	}
	cancelA()
	select {
	case <-doneA:
	case <-time.After(3 * time.Second):
		t.Fatal("worker-a 未在 ctx 取消后退出")
	}
	crashed := getDetail(t, svc, job.Job.ID)
	if crashed.Job.Status != StatusApplying {
		t.Fatalf("崩溃后状态 = %s，期望 applying", crashed.Job.Status)
	}
	if step, ok := findStep(crashed, "apply"); !ok || step.Status != StepRunning {
		t.Fatalf("崩溃后 apply 步骤状态异常: %+v", step)
	}

	clock.Advance(2 * time.Second)
	workerB := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-b", time.Minute)
	ctxB, cancelB := context.WithCancel(context.Background())
	defer cancelB()
	claimedB, ok, err := svc.Claim(ctxB, KindAssetResearch, workerB.newLeaseToken(), time.Minute)
	if err != nil || !ok {
		t.Fatalf("worker-b 接管失败: ok=%v err=%v", ok, err)
	}
	if claimedB.Job.Status != StatusApplying || !strings.HasPrefix(claimedB.Job.LeaseOwner, "worker-b/") {
		t.Fatalf("接管结果异常: status=%s owner=%s", claimedB.Job.Status, claimedB.Job.LeaseOwner)
	}
	if claimedB.Job.LeaseOwner == claimedA.Job.LeaseOwner {
		t.Fatalf("接管后租约令牌未更换: %s", claimedB.Job.LeaseOwner)
	}
	workerB.process(ctxB, claimedB, claimedB.Job.LeaseOwner)

	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusCompleted {
		t.Fatalf("恢复后终态 = %s，期望 completed", final.Job.Status)
	}
	if got := handler.count("inventory"); got != 1 {
		t.Fatalf("已完成步骤 inventory 重复执行 %d 次（期望 1）", got)
	}
	if got := handler.count("apply"); got != 2 {
		t.Fatalf("apply 执行次数 = %d，期望 2（崩溃一次 + 恢复重跑一次）", got)
	}
	if got := handler.count("verify"); got != 1 {
		t.Fatalf("verify 执行次数 = %d，期望 1", got)
	}
	if step, ok := findStep(final, "apply"); !ok || step.Attempt != 2 || step.Status != StepCompleted {
		t.Fatalf("apply 步骤恢复记录异常: %+v", step)
	}
}

// TestWorkerPauseStopsAndResumeContinues 覆盖：暂停后不再启动新步骤但保留已
// 提交步骤；resume 重新入队后跳过已完成步骤继续跑完。
func TestWorkerPauseStopsAndResumeContinues(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreateKind(t, svc, KindAssetResearch, "worker-pause")
	// 只在第一次执行 apply 时暂停：resume 后会重新执行该步骤（暂停时租约已被
	// 清空，步骤完成写入被 fencing 拒绝，账本仍停留在 running），不能再次暂停。
	var pauseOnce atomic.Bool
	pauseOnce.Store(true)
	handler := newRecordingHandler(workerTestSteps)
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if spec.Name == "apply" && pauseOnce.CompareAndSwap(true, false) {
			if _, err := svc.Pause(ctx, job.ID); err != nil {
				return StepOutcome{}, err
			}
		}
		return StepOutcome{}, nil
	}
	worker := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-pause", time.Minute)
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未领取到任务")
	}
	paused := getDetail(t, svc, job.Job.ID)
	if paused.Job.Status != StatusPaused {
		t.Fatalf("暂停后状态 = %s，期望 paused", paused.Job.Status)
	}
	if paused.Job.LeaseOwner != "" || paused.Job.LeaseUntil != nil {
		t.Fatalf("暂停后租约未清空: owner=%q until=%v", paused.Job.LeaseOwner, paused.Job.LeaseUntil)
	}
	applyStep, ok := findStep(paused, "apply")
	if !ok || applyStep.Status != StepRunning {
		t.Fatalf("暂停时进行中的 apply 步骤应保留为 running（完成写入被租约围栏拒绝）: %+v", applyStep)
	}
	if handler.count("verify") != 0 {
		t.Fatal("暂停后仍执行了 verify 步骤")
	}

	resumed, err := svc.Resume(ctx, job.Job.ID)
	if err != nil || resumed.Job.Status != StatusQueued {
		t.Fatalf("恢复入队失败: status=%s err=%v", resumed.Job.Status, err)
	}
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("resume 后 worker 未重新领取任务")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusCompleted {
		t.Fatalf("恢复后终态 = %s，期望 completed", final.Job.Status)
	}
	if handler.count("inventory") != 1 {
		t.Fatalf("已完成步骤 inventory 被重复执行: %d", handler.count("inventory"))
	}
	if handler.count("apply") != 2 {
		t.Fatalf("apply 执行次数 = %d，期望 2（暂停打断一次 + resume 重跑一次）", handler.count("apply"))
	}
	if handler.count("verify") != 1 {
		t.Fatalf("verify 执行次数 = %d，期望 1", handler.count("verify"))
	}
}

// TestWorkerCancelStops 覆盖：取消（终态 cancelled）后不再推进后续步骤，已
// 提交步骤保留，worker 不得把终态改写为 completed/failed。
func TestWorkerCancelStops(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreateKind(t, svc, KindAssetResearch, "worker-cancel")
	handler := newRecordingHandler(workerTestSteps)
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if spec.Name == "apply" {
			if _, err := svc.Cancel(ctx, job.ID); err != nil {
				return StepOutcome{}, err
			}
		}
		return StepOutcome{}, nil
	}
	worker := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-cancel", time.Minute)
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未领取到任务")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusCancelled || final.Job.FinishedAt == nil {
		t.Fatalf("取消后终态异常: status=%s finishedAt=%v", final.Job.Status, final.Job.FinishedAt)
	}
	if handler.count("verify") != 0 {
		t.Fatal("取消后仍执行了 verify 步骤")
	}
	applyStep, ok := findStep(final, "apply")
	if !ok || applyStep.Status != StepRunning {
		t.Fatalf("取消时进行中的 apply 步骤应保留为 running（完成写入被租约围栏拒绝）: %+v", applyStep)
	}
}

// TestWorkerOnlyClaimsRegisteredKinds 覆盖：未注册 handler 的 kind 保持
// queued（留给外部执行器），不会被本 worker 消费。
func TestWorkerOnlyClaimsRegisteredKinds(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	unregistered := mustCreateKind(t, svc, KindAssetResearch, "worker-unregistered")
	custom := mustCreateKind(t, svc, KindCustom, "worker-custom")

	handler := newRecordingHandler([]StepSpec{{Name: "inventory", Stage: StatusInventory}})
	worker := newTestWorker(t, svc, clock, handler, KindCustom, "worker-kind", time.Minute)
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未领取到已注册 kind 的任务")
	}
	stillQueued := getDetail(t, svc, unregistered.Job.ID)
	if stillQueued.Job.Status != StatusQueued || stillQueued.Job.StepCount != 0 {
		t.Fatalf("未注册 kind 被消费: status=%s steps=%d", stillQueued.Job.Status, stillQueued.Job.StepCount)
	}
	finished := getDetail(t, svc, custom.Job.ID)
	if finished.Job.Status != StatusCompleted {
		t.Fatalf("已注册 kind 终态 = %s，期望 completed", finished.Job.Status)
	}
}

// TestWorkerFailureTerminal 覆盖：handler 失败 -> 步骤 failed + 任务 failed
// 终态，错误码/错误信息可回查。
func TestWorkerFailureTerminal(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreateKind(t, svc, KindAssetResearch, "worker-failure")
	handler := newRecordingHandler(workerTestSteps)
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if spec.Name == "apply" {
			return StepOutcome{}, &HandlerError{Code: "SYNTHETIC_FAILED", Err: errors.New("synthetic boom")}
		}
		return StepOutcome{}, nil
	}
	worker := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-failure", time.Minute)
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未领取到任务")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusFailed || final.Job.FinishedAt == nil {
		t.Fatalf("失败终态异常: status=%s finishedAt=%v", final.Job.Status, final.Job.FinishedAt)
	}
	if final.Job.ErrorCode != "SYNTHETIC_FAILED" {
		t.Fatalf("errorCode = %q，期望 SYNTHETIC_FAILED", final.Job.ErrorCode)
	}
	step, ok := findStep(final, "apply")
	if !ok || step.Status != StepFailed {
		t.Fatalf("失败步骤记录异常: %+v", step)
	}
	if handler.count("verify") != 0 {
		t.Fatal("失败后仍执行了后续步骤")
	}
}

// TestWorkerPanicRecovery 覆盖：步骤 panic 由 worker 兜底为 failed 终态，只
// 影响当前任务（不向测试 goroutine 传播）。
func TestWorkerPanicRecovery(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreateKind(t, svc, KindAssetResearch, "worker-panic")
	handler := newRecordingHandler(workerTestSteps)
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if spec.Name == "apply" {
			panic("synthetic panic")
		}
		return StepOutcome{}, nil
	}
	worker := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-panic", time.Minute)
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未领取到任务")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusFailed || final.Job.ErrorCode != WorkerPanicCode {
		t.Fatalf("panic 兜底终态异常: status=%s code=%q", final.Job.Status, final.Job.ErrorCode)
	}
	if step, ok := findStep(final, "apply"); !ok || step.Status != StepFailed {
		t.Fatalf("panic 步骤记录异常: %+v", step)
	}
}

// TestWorkerPartialWhenStepSkipped 覆盖：步骤无副作用跳过 -> partial 终态。
func TestWorkerPartialWhenStepSkipped(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreateKind(t, svc, KindAssetResearch, "worker-partial")
	handler := newRecordingHandler(workerTestSteps)
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if spec.Name == "verify" {
			return StepOutcome{Skip: true}, nil
		}
		return StepOutcome{}, nil
	}
	worker := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-partial", time.Minute)
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未领取到任务")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusPartial {
		t.Fatalf("skip 后终态 = %s，期望 partial", final.Job.Status)
	}
	if step, ok := findStep(final, "verify"); !ok || step.Status != StepSkipped {
		t.Fatalf("skip 步骤记录异常: %+v", step)
	}
}

// syntheticBackupManager 构造临时目录 + 合成快照执行器的 backup.Manager
// （不碰真实库与 8420）。
func syntheticBackupManager(t *testing.T, clock *testClock, content []byte, fail bool) *backup.Manager {
	t.Helper()
	manager, err := backup.NewManager(backup.Options{
		Dir: filepath.Join(t.TempDir(), backup.DirName),
		Snapshot: func(ctx context.Context, dest string) error {
			if fail {
				return errors.New("synthetic snapshot failure")
			}
			return os.WriteFile(dest, content, 0o600)
		},
		Retention: 7,
		Logger:    discardLogger(),
		Now:       clock.Now,
	})
	if err != nil {
		t.Fatalf("构造合成备份管理器失败: %v", err)
	}
	return manager
}

// TestWorkerBackupExportArtifact 覆盖内置 backup_export handler：快照成功 ->
// 工件记录 name/sha256/bytes + verify 回读通过 -> completed 终态。
func TestWorkerBackupExportArtifact(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	content := []byte("synthetic-sqlite-snapshot-bytes")
	sum := sha256.Sum256(content)
	wantSHA := hex.EncodeToString(sum[:])
	manager := syntheticBackupManager(t, clock, content, false)
	job := mustCreateKind(t, svc, KindBackupExport, "worker-backup-export")

	worker := NewWorker(WorkerOptions{
		Service: svc, Backups: manager, Logger: discardLogger(), Owner: "worker-backup",
		LeaseTTL: time.Minute, Now: clock.Now,
	})
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未领取到 backup_export 任务")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusCompleted {
		t.Fatalf("backup_export 终态 = %s，期望 completed（error=%s/%s）",
			final.Job.Status, final.Job.ErrorCode, final.Job.ErrorMessage)
	}
	if len(final.Artifacts) != 1 {
		t.Fatalf("工件数 = %d，期望 1", len(final.Artifacts))
	}
	artifact := final.Artifacts[0]
	if artifact.Kind != BackupArtifactKind {
		t.Fatalf("工件 kind = %q，期望 %q", artifact.Kind, BackupArtifactKind)
	}
	if !regexp.MustCompile(`^qimeng-\d{8}-\d{6}\.db$`).MatchString(artifact.URI) {
		t.Fatalf("工件 uri = %q，不是快照文件名", artifact.URI)
	}
	if artifact.SHA256 != wantSHA {
		t.Fatalf("工件 sha256 = %q，期望 %q", artifact.SHA256, wantSHA)
	}
	if artifact.Bytes != int64(len(content)) {
		t.Fatalf("工件 bytes = %d，期望 %d", artifact.Bytes, len(content))
	}
	if verifyStep, ok := findStep(final, "verify"); !ok || verifyStep.Status != StepCompleted {
		t.Fatalf("verify 步骤未完成: %+v", verifyStep)
	}
}

// TestWorkerBackupExportFailure 覆盖内置 handler 的快照失败路径：export 步骤
// failed + 任务 failed 终态，不产生工件。
func TestWorkerBackupExportFailure(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	manager := syntheticBackupManager(t, clock, nil, true)
	job := mustCreateKind(t, svc, KindBackupExport, "worker-backup-failure")
	worker := NewWorker(WorkerOptions{
		Service: svc, Backups: manager, Logger: discardLogger(), Owner: "worker-backup-fail",
		LeaseTTL: time.Minute, Now: clock.Now,
	})
	if !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未领取到 backup_export 任务")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusFailed {
		t.Fatalf("快照失败后终态 = %s，期望 failed", final.Job.Status)
	}
	if final.Job.ErrorCode != backupExportCreateFailedCode {
		t.Fatalf("errorCode = %q，期望 %q", final.Job.ErrorCode, backupExportCreateFailedCode)
	}
	if len(final.Artifacts) != 0 {
		t.Fatalf("失败任务不应记录工件: %+v", final.Artifacts)
	}
	if step, ok := findStep(final, "export"); !ok || step.Status != StepFailed {
		t.Fatalf("export 步骤未记录失败: %+v", step)
	}
}

// TestWorkerStartProcessesAndStops 覆盖 Start 生命周期：常驻轮询消费 queued
// 任务，ctx 取消后在期限内返回；并发=1 时第二个任务不会被同时领取。
func TestWorkerStartProcessesAndStops(t *testing.T) {
	svc, clock := newTestService(t)
	first := mustCreateKind(t, svc, KindAssetResearch, "worker-start-1")
	// 推进 fake 时钟保证「最旧优先」领取顺序确定（同 created_at 时按随机
	// UUID 排序，会让本测试依赖运气）。
	clock.Advance(time.Second)
	second := mustCreateKind(t, svc, KindAssetResearch, "worker-start-2")

	entered := make(chan struct{})
	release := make(chan struct{})
	var enteredOnce sync.Once
	handler := newRecordingHandler([]StepSpec{{Name: "inventory", Stage: StatusInventory}})
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if job.ID == first.Job.ID {
			enteredOnce.Do(func() { close(entered) })
			<-release
		}
		return StepOutcome{}, nil
	}
	worker := NewWorker(WorkerOptions{
		Service: svc, Logger: discardLogger(), Owner: "worker-start",
		LeaseTTL: time.Minute, PollInterval: 10 * time.Millisecond, Concurrency: 1,
		Now: clock.Now, Handlers: map[Kind]Handler{KindAssetResearch: handler},
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		worker.Start(ctx)
	}()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("worker.Start 未开始消费任务")
	}
	// 并发=1：第一个任务占住唯一槽位时第二个必须仍在 queued。
	time.Sleep(30 * time.Millisecond)
	other := getDetail(t, svc, second.Job.ID)
	if other.Job.Status != StatusQueued {
		t.Fatalf("并发=1 时第二个任务状态 = %s，期望 queued", other.Job.Status)
	}
	close(release)
	waitFor(t, 3*time.Second, "两个任务完成", func() bool {
		one := getDetail(t, svc, first.Job.ID)
		two := getDetail(t, svc, second.Job.ID)
		return one.Job.Status == StatusCompleted && two.Job.Status == StatusCompleted
	})
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("worker.Start 在 ctx 取消后未退出")
	}
}

// TestWorkerIdentityAndLeaseTokenUnique 覆盖唯一实例身份与每次领取的 fencing
// token：默认 owner 含 pid+UUID，不同实例/不同领取均不相同。
func TestWorkerIdentityAndLeaseTokenUnique(t *testing.T) {
	workerA := NewWorker(WorkerOptions{})
	workerB := NewWorker(WorkerOptions{})
	if workerA.owner == "" || workerB.owner == "" {
		t.Fatal("默认 worker owner 为空")
	}
	if workerA.owner == workerB.owner {
		t.Fatalf("两个 worker 实例共享 owner: %s", workerA.owner)
	}
	if !strings.HasPrefix(workerA.owner, "qimeng-worker-") {
		t.Fatalf("owner 前缀异常: %s", workerA.owner)
	}
	first := workerA.newLeaseToken()
	second := workerA.newLeaseToken()
	if first == second {
		t.Fatal("同一实例两次领取生成了相同租约令牌")
	}
	if !strings.HasPrefix(first, workerA.owner+"/") || first == workerA.owner {
		t.Fatalf("租约令牌未带实例前缀/fencing 后缀: %s", first)
	}
	// 显式传入的实例前缀仍被保留，但每次领取 token 唯一。
	explicit := NewWorker(WorkerOptions{Owner: "synthetic-instance"})
	if !strings.HasPrefix(explicit.newLeaseToken(), "synthetic-instance/") {
		t.Fatal("显式 owner 前缀未保留")
	}
}

// TestWorkerLeaseTokenChangesPerClaim 覆盖同一实例连续领取两笔任务时账本
// 记录不同租约令牌（回归「owner 字符串重复导致旧执行器继续续租」）。
func TestWorkerLeaseTokenChangesPerClaim(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	first := mustCreateKind(t, svc, KindAssetResearch, "worker-token-1")
	clock.Advance(time.Second)
	second := mustCreateKind(t, svc, KindAssetResearch, "worker-token-2")

	owners := make(map[string]string)
	var mu sync.Mutex
	handler := newRecordingHandler([]StepSpec{{Name: "inventory", Stage: StatusInventory}})
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		current := getDetail(t, svc, job.ID)
		mu.Lock()
		owners[job.ID] = current.Job.LeaseOwner
		mu.Unlock()
		return StepOutcome{}, nil
	}
	worker := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-token", time.Minute)
	if !worker.claimAndProcessOne(ctx) || !worker.claimAndProcessOne(ctx) {
		t.Fatal("worker 未连续领取两笔任务")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(owners) != 2 {
		t.Fatalf("记录到的租约数 = %d，期望 2", len(owners))
	}
	firstOwner, ok := owners[first.Job.ID]
	if !ok || !strings.HasPrefix(firstOwner, "worker-token/") {
		t.Fatalf("第一笔租约令牌异常: %q", firstOwner)
	}
	secondOwner := owners[second.Job.ID]
	if secondOwner == firstOwner {
		t.Fatalf("同一实例两次领取复用了租约令牌: %q", firstOwner)
	}
}

// TestStaleLeaseCannotOverwriteTakeoverResult 覆盖 fencing 的核心回归：旧执行
// 上下文在租约过期、任务被新实例接管并完成后，不得再写入步骤/工件/终态。
func TestStaleLeaseCannotOverwriteTakeoverResult(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreateKind(t, svc, KindAssetResearch, "worker-stale-fence")

	var firstApply atomic.Bool
	firstApply.Store(true)
	applyEntered := make(chan struct{})
	release := make(chan struct{})
	var enteredOnce sync.Once
	handler := newRecordingHandler(workerTestSteps)
	handler.hook = func(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
		if spec.Name == "apply" && firstApply.CompareAndSwap(true, false) {
			enteredOnce.Do(func() { close(applyEntered) })
			<-release
			// 旧执行器在步骤完成后仍会尝试写工件：租约围栏必须拒绝。
			return StepOutcome{Artifacts: []ArtifactInput{{
				Kind: "stale", URI: "stale://artifact/1",
				SHA256: strings.Repeat("a", 64), Bytes: 1,
			}}}, nil
		}
		return StepOutcome{}, nil
	}
	workerA := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-stale", time.Second)
	doneA := make(chan struct{})
	go func() {
		defer close(doneA)
		workerA.claimAndProcessOne(ctx)
	}()
	select {
	case <-applyEntered:
	case <-time.After(3 * time.Second):
		t.Fatal("旧实例未进入 apply 步骤")
	}
	stale := getDetail(t, svc, job.Job.ID)
	staleOwner := stale.Job.LeaseOwner
	if !strings.HasPrefix(staleOwner, "worker-stale/") {
		t.Fatalf("旧实例租约令牌异常: %q", staleOwner)
	}

	// 租约过期（超过 TTL），新实例重领并完整执行、写完终态。
	clock.Advance(2 * time.Second)
	workerB := newTestWorker(t, svc, clock, handler, KindAssetResearch, "worker-takeover", time.Minute)
	if !workerB.claimAndProcessOne(ctx) {
		t.Fatal("新实例未接管过期任务")
	}
	takenOver := getDetail(t, svc, job.Job.ID)
	if takenOver.Job.Status != StatusCompleted {
		t.Fatalf("接管后终态 = %s，期望 completed", takenOver.Job.Status)
	}

	// 放行旧执行器：它只能停止，不能覆盖新实例已提交的结果。
	close(release)
	select {
	case <-doneA:
	case <-time.After(3 * time.Second):
		t.Fatal("旧实例未在租约丢失后退出")
	}
	final := getDetail(t, svc, job.Job.ID)
	if final.Job.Status != StatusCompleted {
		t.Fatalf("旧实例覆盖了终态: %s", final.Job.Status)
	}
	step, ok := findStep(final, "apply")
	if !ok || step.Attempt != 2 || step.Status != StepCompleted {
		t.Fatalf("旧实例覆盖了新实例的步骤证据: %+v", step)
	}
	for _, artifact := range final.Artifacts {
		if artifact.URI == "stale://artifact/1" {
			t.Fatalf("旧实例工件被写入账本: %+v", artifact)
		}
	}
}
