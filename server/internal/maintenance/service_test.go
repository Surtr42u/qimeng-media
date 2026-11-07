package maintenance

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

type testClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *testClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

func newTestService(t *testing.T) (*Service, *testClock) {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "maintenance-test.db"))
	if err != nil {
		t.Fatalf("打开临时库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移临时库失败: %v", err)
	}
	clock := &testClock{now: time.Date(2026, 11, 7, 0, 0, 0, 0, time.UTC)}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return New(Options{Conn: conn, Queries: db.New(conn), Now: clock.Now, Logger: logger}), clock
}

func mustCreate(t *testing.T, svc *Service, key string) Detail {
	t.Helper()
	detail, created, err := svc.Create(context.Background(), CreateInput{
		Kind:           KindAssetResearch,
		LibraryID:      "lib-test",
		Payload:        json.RawMessage(`{"query":"synthetic"}`),
		IdempotencyKey: key,
		RulesVersion:   "2026-11-07.1",
		Budget:         json.RawMessage(`{"maxSteps":5}`),
	})
	if err != nil {
		t.Fatalf("Create(%q) 失败: %v", key, err)
	}
	if !created {
		t.Fatalf("Create(%q) 期望新建", key)
	}
	return detail
}

func TestCreateAndIdempotentReplay(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	in := CreateInput{
		Kind:           KindAssetResearch,
		LibraryID:      "lib-test",
		Payload:        json.RawMessage(`{"query":"synthetic"}`),
		IdempotencyKey: "idem-1",
		RulesVersion:   "2026-11-07.1",
		Budget:         json.RawMessage(`{"maxSteps":5}`),
	}
	first, created, err := svc.Create(ctx, in)
	if err != nil || !created {
		t.Fatalf("首次创建失败: created=%v err=%v", created, err)
	}
	if first.Job.Status != StatusQueued {
		t.Fatalf("初始状态 = %s，期望 queued", first.Job.Status)
	}
	if first.Job.InputFingerprint == "" {
		t.Fatal("输入指纹为空")
	}
	if string(first.Job.Payload) != `{"query":"synthetic"}` {
		t.Fatalf("payload 归一化异常: %s", first.Job.Payload)
	}

	second, created, err := svc.Create(ctx, in)
	if err != nil || created {
		t.Fatalf("重复幂等键应返回既有任务: created=%v err=%v", created, err)
	}
	if second.Job.ID != first.Job.ID {
		t.Fatalf("重复幂等键返回了不同任务: %s != %s", second.Job.ID, first.Job.ID)
	}
	if second.Job.InputFingerprint != first.Job.InputFingerprint {
		t.Fatal("重复幂等键的指纹不一致")
	}

	third, created, err := svc.Create(ctx, CreateInput{
		Kind:           KindAuthorCleanup,
		IdempotencyKey: "idem-2",
		Payload:        json.RawMessage(`{}`),
	})
	if err != nil || !created {
		t.Fatalf("不同幂等键创建失败: created=%v err=%v", created, err)
	}
	if third.Job.ID == first.Job.ID {
		t.Fatal("不同幂等键复用了任务 ID")
	}
}

func TestCreateRejectsInvalidInput(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	cases := []CreateInput{
		{Kind: "unknown", IdempotencyKey: "k"},
		{Kind: KindCustom, IdempotencyKey: " "},
		{Kind: KindCustom, IdempotencyKey: "k", Payload: json.RawMessage(`[]`)},
		{Kind: KindCustom, IdempotencyKey: "k", Payload: json.RawMessage(`{"a":`)},
		{Kind: KindCustom, IdempotencyKey: strings.Repeat("k", MaxIdempotencyKeyRunes+1)},
	}
	for i, in := range cases {
		if _, _, err := svc.Create(ctx, in); !errors.Is(err, ErrInvalidInput) {
			t.Fatalf("用例 %d 期望 ErrInvalidInput，得到 %v", i, err)
		}
	}
}

func TestListFilterAndCursor(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	first := mustCreate(t, svc, "list-1")
	clock.Advance(time.Second)
	second := mustCreate(t, svc, "list-2")
	clock.Advance(time.Second)
	third := mustCreate(t, svc, "list-3")
	if _, err := svc.Pause(ctx, second.Job.ID); err != nil {
		t.Fatalf("暂停第二笔失败: %v", err)
	}

	all, err := svc.List(ctx, ListOptions{Limit: 2})
	if err != nil {
		t.Fatalf("列表失败: %v", err)
	}
	if len(all.Items) != 2 || all.NextCursor == "" {
		t.Fatalf("首页 = %d 条, cursor=%q，期望 2 条且有下一页", len(all.Items), all.NextCursor)
	}
	if all.Items[0].ID != third.Job.ID || all.Items[1].ID != second.Job.ID {
		t.Fatalf("首页顺序异常: %s, %s", all.Items[0].ID, all.Items[1].ID)
	}
	page2, err := svc.List(ctx, ListOptions{Limit: 2, Cursor: all.NextCursor})
	if err != nil {
		t.Fatalf("第二页失败: %v", err)
	}
	if len(page2.Items) != 1 || page2.Items[0].ID != first.Job.ID || page2.NextCursor != "" {
		t.Fatalf("第二页异常: %+v", page2)
	}

	paused, err := svc.List(ctx, ListOptions{Status: StatusPaused, Limit: 10})
	if err != nil {
		t.Fatalf("按状态过滤失败: %v", err)
	}
	if len(paused.Items) != 1 || paused.Items[0].ID != second.Job.ID {
		t.Fatalf("paused 过滤异常: %+v", paused.Items)
	}
	if paused.Items[0].StepCount != 0 || paused.Items[0].Progress != 0 {
		t.Fatalf("空步骤进度异常: stepCount=%d progress=%d", paused.Items[0].StepCount, paused.Items[0].Progress)
	}

	if _, err := svc.List(ctx, ListOptions{Cursor: "not-a-cursor"}); !errors.Is(err, ErrInvalidCursor) {
		t.Fatalf("非法游标期望 ErrInvalidCursor，得到 %v", err)
	}
	if _, err := svc.List(ctx, ListOptions{Status: "bogus"}); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("非法状态期望 ErrInvalidInput，得到 %v", err)
	}
	if _, err := svc.List(ctx, ListOptions{Limit: MaxListLimit + 1}); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("超限 limit 期望 ErrInvalidInput，得到 %v", err)
	}
}

func TestPublicStateMachine(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "state-1")

	paused, err := svc.Pause(ctx, job.Job.ID)
	if err != nil || paused.Job.Status != StatusPaused {
		t.Fatalf("暂停失败: status=%s err=%v", paused.Job.Status, err)
	}
	if _, err := svc.Pause(ctx, job.Job.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("重复暂停期望 ErrStateConflict，得到 %v", err)
	}
	resumed, err := svc.Resume(ctx, job.Job.ID)
	if err != nil || resumed.Job.Status != StatusQueued {
		t.Fatalf("恢复失败: status=%s err=%v", resumed.Job.Status, err)
	}
	if _, err := svc.Resume(ctx, job.Job.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("非 paused 恢复期望 ErrStateConflict，得到 %v", err)
	}
	cancelled, err := svc.Cancel(ctx, job.Job.ID)
	if err != nil || cancelled.Job.Status != StatusCancelled {
		t.Fatalf("取消失败: status=%s err=%v", cancelled.Job.Status, err)
	}
	if cancelled.Job.FinishedAt == nil {
		t.Fatal("取消后 finishedAt 为空")
	}
	if _, err := svc.Cancel(ctx, job.Job.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("重复取消期望 ErrStateConflict，得到 %v", err)
	}
	if _, err := svc.Pause(ctx, job.Job.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("终态暂停期望 ErrStateConflict，得到 %v", err)
	}
	if _, err := svc.Resume(ctx, job.Job.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("终态恢复期望 ErrStateConflict，得到 %v", err)
	}
}

func TestNotFound(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	missing := "00000000-0000-4000-8000-000000000000"
	if _, err := svc.Get(ctx, missing); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Get 期望 ErrNotFound，得到 %v", err)
	}
	if _, err := svc.Pause(ctx, missing); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Pause 期望 ErrNotFound，得到 %v", err)
	}
	if _, err := svc.Resume(ctx, missing); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Resume 期望 ErrNotFound，得到 %v", err)
	}
	if _, err := svc.Cancel(ctx, missing); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Cancel 期望 ErrNotFound，得到 %v", err)
	}
}

func TestConcurrentCreateSameIdempotencyKey(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	in := CreateInput{
		Kind:           KindBackupExport,
		Payload:        json.RawMessage(`{"target":"synthetic"}`),
		IdempotencyKey: "concurrent-key",
	}
	const workers = 8
	var wg sync.WaitGroup
	ids := make(chan string, workers)
	createdCount := make(chan bool, workers)
	errs := make(chan error, workers)
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			detail, created, err := svc.Create(ctx, in)
			if err != nil {
				errs <- err
				return
			}
			ids <- detail.Job.ID
			createdCount <- created
		}()
	}
	wg.Wait()
	close(errs)
	close(ids)
	close(createdCount)
	for err := range errs {
		t.Fatalf("并发创建失败: %v", err)
	}
	var wantID string
	created := 0
	for id := range ids {
		if wantID == "" {
			wantID = id
		}
		if id != wantID {
			t.Fatalf("并发幂等键返回了不同任务: %s != %s", id, wantID)
		}
	}
	for value := range createdCount {
		if value {
			created++
		}
	}
	if created != 1 {
		t.Fatalf("并发创建 new=true 次数 = %d，期望 1", created)
	}
	list, err := svc.List(ctx, ListOptions{Limit: 10})
	if err != nil {
		t.Fatalf("列表失败: %v", err)
	}
	if len(list.Items) != 1 {
		t.Fatalf("并发后任务数 = %d，期望 1", len(list.Items))
	}
}

func TestConcurrentPauseAndCancelFinalState(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "race-state")
	var wg sync.WaitGroup
	wg.Add(2)
	results := make(chan error, 2)
	go func() {
		defer wg.Done()
		_, err := svc.Pause(ctx, job.Job.ID)
		results <- err
	}()
	go func() {
		defer wg.Done()
		_, err := svc.Cancel(ctx, job.Job.ID)
		results <- err
	}()
	wg.Wait()
	close(results)
	success := 0
	for err := range results {
		switch {
		case err == nil:
			success++
		case errors.Is(err, ErrStateConflict):
		default:
			t.Fatalf("竞态返回异常错误: %v", err)
		}
	}
	// pause 与 cancel 都允许从 queued 出发；事务串行化后，若 pause 先提交，
	// cancel 仍可把 paused 取消，因此两次都可能成功，但最终状态必为 cancelled。
	if success < 1 || success > 2 {
		t.Fatalf("并发状态迁移成功数 = %d，期望 1 或 2", success)
	}
	detail, err := svc.Get(ctx, job.Job.ID)
	if err != nil {
		t.Fatalf("回读失败: %v", err)
	}
	if detail.Job.Status != StatusCancelled {
		t.Fatalf("并发 pause/cancel 后最终状态应为 cancelled，得到 %s", detail.Job.Status)
	}
}
func TestLeaseClaimHeartbeatComplete(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "lease-1")

	claimed, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-a", time.Minute)
	if err != nil || !ok {
		t.Fatalf("Claim 失败: ok=%v err=%v", ok, err)
	}
	if claimed.Job.ID != job.Job.ID {
		t.Fatalf("Claim 返回了错误任务: %s != %s", claimed.Job.ID, job.Job.ID)
	}
	if claimed.Job.LeaseOwner != "worker-a" || claimed.Job.LeaseUntil == nil {
		t.Fatalf("租约字段未写入: owner=%q until=%v", claimed.Job.LeaseOwner, claimed.Job.LeaseUntil)
	}
	if err := svc.Heartbeat(ctx, job.Job.ID, "worker-b", time.Minute); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("非持有者心跳期望 ErrLeaseLost，得到 %v", err)
	}
	if err := svc.Heartbeat(ctx, job.Job.ID, "worker-a", time.Minute); err != nil {
		t.Fatalf("持有者心跳失败: %v", err)
	}
	if _, err := svc.Complete(ctx, job.Job.ID, "worker-b", StatusCompleted, "", ""); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("非持有者完成期望 ErrLeaseLost，得到 %v", err)
	}
	completed, err := svc.Complete(ctx, job.Job.ID, "worker-a", StatusCompleted, "IGNORED", "ignored")
	if err != nil {
		t.Fatalf("完成失败: %v", err)
	}
	if completed.Job.Status != StatusCompleted || completed.Job.FinishedAt == nil {
		t.Fatalf("完成状态异常: status=%s finishedAt=%v", completed.Job.Status, completed.Job.FinishedAt)
	}
	if completed.Job.ErrorCode != "" || completed.Job.ErrorMessage != "" {
		t.Fatalf("completed 未清空错误: %q/%q", completed.Job.ErrorCode, completed.Job.ErrorMessage)
	}
	if _, err := svc.Pause(ctx, job.Job.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("终态暂停期望 ErrStateConflict，得到 %v", err)
	}

	// 租约过期后可被另一 worker 接管。
	second := mustCreate(t, svc, "lease-2")
	if _, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-a", time.Second); err != nil || !ok {
		t.Fatalf("第二次 Claim 失败: ok=%v err=%v", ok, err)
	}
	clock.Advance(2 * time.Second)
	reclaimed, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-b", time.Minute)
	if err != nil || !ok {
		t.Fatalf("过期接管失败: ok=%v err=%v", ok, err)
	}
	if reclaimed.Job.ID != second.Job.ID || reclaimed.Job.LeaseOwner != "worker-b" {
		t.Fatalf("过期接管异常: id=%s owner=%s", reclaimed.Job.ID, reclaimed.Job.LeaseOwner)
	}
}

func TestRecordStepAndArtifactIndex(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "index-1")
	started := clock.Now()
	if err := svc.RecordStep(ctx, StepInput{
		JobID: job.Job.ID, Step: "inventory", Status: StepRunning,
		Attempt: 1, StartedAt: &started,
		Detail: json.RawMessage(`{"scanned":10}`),
	}); err != nil {
		t.Fatalf("记录步骤失败: %v", err)
	}
	finished := started.Add(time.Second)
	if err := svc.RecordStep(ctx, StepInput{
		JobID: job.Job.ID, Step: "inventory", Status: StepCompleted,
		Attempt: 1, StartedAt: &started, FinishedAt: &finished,
		Detail: json.RawMessage(`{"scanned":10,"matched":3}`),
	}); err != nil {
		t.Fatalf("更新步骤失败: %v", err)
	}
	sha := strings.Repeat("a", 64)
	if err := svc.AddArtifact(ctx, ArtifactInput{
		JobID: job.Job.ID, Kind: "report", URI: "synthetic://report/1",
		SHA256: sha, Bytes: 12, CreatedAt: clock.Now(),
	}); err != nil {
		t.Fatalf("记录工件失败: %v", err)
	}
	if err := svc.AddArtifact(ctx, ArtifactInput{
		JobID: job.Job.ID, Kind: "report", URI: "synthetic://report/1",
		SHA256: sha, Bytes: 12, CreatedAt: clock.Now(),
	}); err != nil {
		t.Fatalf("重复工件应幂等: %v", err)
	}
	if err := svc.AddArtifact(ctx, ArtifactInput{
		JobID: job.Job.ID, Kind: "report", URI: "synthetic://bad", SHA256: "not-sha",
	}); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("非法 sha256 期望 ErrInvalidInput，得到 %v", err)
	}
	detail, err := svc.Get(ctx, job.Job.ID)
	if err != nil {
		t.Fatalf("回读失败: %v", err)
	}
	if detail.Job.StepCount != 1 || len(detail.Steps) != 1 {
		t.Fatalf("步骤索引异常: count=%d len=%d", detail.Job.StepCount, len(detail.Steps))
	}
	if detail.Steps[0].Status != StepCompleted || detail.Steps[0].Attempt != 1 {
		t.Fatalf("步骤内容异常: %+v", detail.Steps[0])
	}
	if len(detail.Artifacts) != 1 || detail.Artifacts[0].SHA256 != sha {
		t.Fatalf("工件索引异常: %+v", detail.Artifacts)
	}
	if detail.Job.Progress != 100 {
		t.Fatalf("进度 = %d，期望 100", detail.Job.Progress)
	}
}

func TestStateTransitionMatrix(t *testing.T) {
	cases := []struct {
		from Status
		to   Status
		want bool
	}{
		{StatusQueued, StatusInventory, true},
		{StatusQueued, StatusVerifying, false},
		{StatusInventory, StatusResearch, true},
		{StatusResearch, StatusPlanned, true},
		{StatusPlanned, StatusApplying, true},
		{StatusApplying, StatusVerifying, true},
		{StatusVerifying, StatusCompleted, true},
		{StatusVerifying, StatusFailed, true},
		{StatusWaitingEvidence, StatusQueued, true},
		{StatusWaitingAuthorization, StatusApplying, true},
		{StatusWaitingDevice, StatusApplying, true},
		{StatusPaused, StatusQueued, true},
		{StatusPaused, StatusApplying, false},
		{StatusCompleted, StatusQueued, false},
		{StatusPartial, StatusCompleted, false},
		{StatusCancelled, StatusQueued, false},
		{StatusFailed, StatusFailed, false},
	}
	for _, tc := range cases {
		if got := tc.from.CanTransitionTo(tc.to); got != tc.want {
			t.Fatalf("%s -> %s = %v，期望 %v", tc.from, tc.to, got, tc.want)
		}
	}
	if !StatusCompleted.Terminal() || !StatusPartial.Terminal() ||
		!StatusFailed.Terminal() || !StatusCancelled.Terminal() {
		t.Fatal("终态判定不完整")
	}
	if StatusQueued.Terminal() || StatusPaused.Terminal() {
		t.Fatal("非终态被判定为终态")
	}
}

func TestTerminalIsImmutableAfterComplete(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "terminal-1")
	if _, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-a", time.Minute); err != nil || !ok {
		t.Fatalf("Claim 失败: ok=%v err=%v", ok, err)
	}
	done, err := svc.Complete(ctx, job.Job.ID, "worker-a", StatusFailed, "SYNTHETIC", "synthetic failure")
	if err != nil {
		t.Fatalf("Complete 失败: %v", err)
	}
	if done.Job.Status != StatusFailed || done.Job.ErrorCode != "SYNTHETIC" {
		t.Fatalf("失败终态异常: %+v", done.Job)
	}
	for name, call := range map[string]func() (Detail, error){
		"pause":  func() (Detail, error) { return svc.Pause(ctx, job.Job.ID) },
		"resume": func() (Detail, error) { return svc.Resume(ctx, job.Job.ID) },
		"cancel": func() (Detail, error) { return svc.Cancel(ctx, job.Job.ID) },
	} {
		if _, err := call(); !errors.Is(err, ErrStateConflict) {
			t.Fatalf("%s 终态期望 ErrStateConflict，得到 %v", name, err)
		}
	}
}

func TestTransactionDoesNotPartiallyApplyIllegalTransition(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "tx-1")
	if _, err := svc.Cancel(ctx, job.Job.ID); err != nil {
		t.Fatalf("取消失败: %v", err)
	}
	before, err := svc.Get(ctx, job.Job.ID)
	if err != nil {
		t.Fatalf("回读失败: %v", err)
	}
	if _, err := svc.Resume(ctx, job.Job.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("终态恢复期望 ErrStateConflict，得到 %v", err)
	}
	after, err := svc.Get(ctx, job.Job.ID)
	if err != nil {
		t.Fatalf("回读失败: %v", err)
	}
	if after.Job.Status != before.Job.Status || !after.Job.UpdatedAt.Equal(before.Job.UpdatedAt) {
		t.Fatalf("非法迁移改动了任务: before=%+v after=%+v", before.Job, after.Job)
	}
}

// TestCreateIdempotencyConflict 覆盖幂等冲突保护：同键同语义输入仍是合法
// 重放（200，created=false）；同键不同 kind/载荷/预算返回 ErrStateConflict
// （HTTP 409），不会把另一笔任务当成成功重放，也不产生第二行。
func TestCreateIdempotencyConflict(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	base := CreateInput{
		Kind:           KindAssetResearch,
		LibraryID:      "lib-test",
		Payload:        json.RawMessage(`{"query":"synthetic","limit":3}`),
		IdempotencyKey: "conflict-1",
		RulesVersion:   "2026-11-07.1",
		Budget:         json.RawMessage(`{"maxSteps":5}`),
	}
	first, created, err := svc.Create(ctx, base)
	if err != nil || !created {
		t.Fatalf("首次创建失败: created=%v err=%v", created, err)
	}

	// 键序不同但归一化后语义相同：指纹一致，保持既有重放语义。
	reordered := base
	reordered.Payload = json.RawMessage(`{"limit":3,"query":"synthetic"}`)
	replay, created, err := svc.Create(ctx, reordered)
	if err != nil || created {
		t.Fatalf("同语义重放应返回既有任务: created=%v err=%v", created, err)
	}
	if replay.Job.ID != first.Job.ID {
		t.Fatalf("同语义重放返回了不同任务: %s != %s", replay.Job.ID, first.Job.ID)
	}

	conflicts := []struct {
		name string
		in   CreateInput
	}{
		{"载荷不同", func() CreateInput {
			in := base
			in.Payload = json.RawMessage(`{"query":"other"}`)
			return in
		}()},
		{"kind 不同", func() CreateInput {
			in := base
			in.Kind = KindAuthorCleanup
			return in
		}()},
		{"预算不同", func() CreateInput {
			in := base
			in.Budget = json.RawMessage(`{"maxSteps":99}`)
			return in
		}()},
		{"库不同", func() CreateInput {
			in := base
			in.LibraryID = "lib-other"
			return in
		}()},
	}
	for _, tc := range conflicts {
		detail, created, err := svc.Create(ctx, tc.in)
		if !errors.Is(err, ErrStateConflict) {
			t.Fatalf("%s 期望 ErrStateConflict，得到 err=%v", tc.name, err)
		}
		if created || detail.Job.ID != "" {
			t.Fatalf("%s 冲突时不应返回任务/新建: created=%v detail=%+v", tc.name, created, detail.Job)
		}
	}
	list, err := svc.List(ctx, ListOptions{Limit: 10})
	if err != nil {
		t.Fatalf("列表失败: %v", err)
	}
	if len(list.Items) != 1 {
		t.Fatalf("冲突后任务数 = %d，期望 1", len(list.Items))
	}
}

// TestClaimFiltersByKind 覆盖按 kind 领取：只领取指定 kind 的最旧任务，其他
// kind 保持 queued（worker 只消费已注册 handler 的 kind 的基础原语语义）。
func TestClaimFiltersByKind(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	research := mustCreate(t, svc, "claim-kind-research")
	clock.Advance(time.Second)
	backup, created, err := svc.Create(ctx, CreateInput{
		Kind: KindBackupExport, IdempotencyKey: "claim-kind-backup",
	})
	if err != nil || !created {
		t.Fatalf("创建 backup_export 任务失败: created=%v err=%v", created, err)
	}

	claimed, ok, err := svc.Claim(ctx, KindBackupExport, "worker-kind", time.Minute)
	if err != nil || !ok {
		t.Fatalf("按 kind 领取失败: ok=%v err=%v", ok, err)
	}
	if claimed.Job.ID != backup.Job.ID {
		t.Fatalf("按 kind 领取到了错误任务: %s != %s", claimed.Job.ID, backup.Job.ID)
	}
	stillQueued, err := svc.Get(ctx, research.Job.ID)
	if err != nil {
		t.Fatalf("回读 research 任务失败: %v", err)
	}
	if stillQueued.Job.Status != StatusQueued {
		t.Fatalf("未指定 kind 的 queued 任务被领取: status=%s", stillQueued.Job.Status)
	}

	claimedResearch, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-kind", time.Minute)
	if err != nil || !ok {
		t.Fatalf("领取 research 任务失败: ok=%v err=%v", ok, err)
	}
	if claimedResearch.Job.ID != research.Job.ID {
		t.Fatalf("领取 research 任务 ID 不符: %s != %s", claimedResearch.Job.ID, research.Job.ID)
	}
	if _, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-kind", time.Minute); err != nil || ok {
		t.Fatalf("租约未过期不应重复领取: ok=%v err=%v", ok, err)
	}
}

// TestAdvanceStatusLeaseAndTransitions 覆盖内部状态推进方法：无租约/非持有者
// ErrLeaseLost，非法迁移 ErrStateConflict，同状态幂等，暂停后不得再推进。
func TestAdvanceStatusLeaseAndTransitions(t *testing.T) {
	svc, _ := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "advance-status")
	if _, err := svc.AdvanceStatus(ctx, job.Job.ID, "worker-a", StatusInventory); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("无租约推进期望 ErrLeaseLost，得到 %v", err)
	}
	if _, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-a", time.Minute); err != nil || !ok {
		t.Fatalf("Claim 失败: ok=%v err=%v", ok, err)
	}
	if _, err := svc.AdvanceStatus(ctx, job.Job.ID, "worker-b", StatusInventory); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("非持有者推进期望 ErrLeaseLost，得到 %v", err)
	}
	advanced, err := svc.AdvanceStatus(ctx, job.Job.ID, "worker-a", StatusInventory)
	if err != nil || advanced.Job.Status != StatusInventory {
		t.Fatalf("queued -> inventory 失败: status=%s err=%v", advanced.Job.Status, err)
	}
	if _, err := svc.AdvanceStatus(ctx, job.Job.ID, "worker-a", StatusCompleted); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("inventory -> completed 期望 ErrStateConflict，得到 %v", err)
	}
	same, err := svc.AdvanceStatus(ctx, job.Job.ID, "worker-a", StatusInventory)
	if err != nil || same.Job.Status != StatusInventory {
		t.Fatalf("同状态推进应为幂等 no-op: status=%s err=%v", same.Job.Status, err)
	}
	applying, err := svc.AdvanceStatus(ctx, job.Job.ID, "worker-a", StatusApplying)
	if err != nil || applying.Job.Status != StatusApplying {
		t.Fatalf("inventory -> applying 失败: status=%s err=%v", applying.Job.Status, err)
	}
	if _, err := svc.Pause(ctx, job.Job.ID); err != nil {
		t.Fatalf("暂停失败: %v", err)
	}
	if _, err := svc.AdvanceStatus(ctx, job.Job.ID, "worker-a", StatusVerifying); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("暂停后推进期望 ErrLeaseLost，得到 %v", err)
	}
}

// TestFencedWritesRequireUnexpiredLease 覆盖账本侧租约围栏：步骤/工件写入、
// 心跳、状态推进与终态都必须同时满足 owner 匹配且租约未过期；过期后旧
// fencing token 不能再续租或写结果，只能由新领取者完成任务。
func TestFencedWritesRequireUnexpiredLease(t *testing.T) {
	svc, clock := newTestService(t)
	ctx := context.Background()
	job := mustCreate(t, svc, "fenced-writes")
	claimed, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-fence/claim-1", time.Minute)
	if err != nil || !ok {
		t.Fatalf("Claim 失败: ok=%v err=%v", ok, err)
	}
	validOwner := claimed.Job.LeaseOwner
	if validOwner != "worker-fence/claim-1" {
		t.Fatalf("租约 owner = %q，期望 worker-fence/claim-1", validOwner)
	}
	started := clock.Now()
	baseStep := StepInput{JobID: job.Job.ID, Step: "inventory", Status: StepRunning, Attempt: 1, StartedAt: &started}
	wrongOwner := baseStep
	wrongOwner.LeaseOwner = "worker-fence/claim-old"
	if err := svc.RecordStep(ctx, wrongOwner); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("非持有者步骤写入期望 ErrLeaseLost，得到 %v", err)
	}
	goodStep := baseStep
	goodStep.LeaseOwner = validOwner
	if err := svc.RecordStep(ctx, goodStep); err != nil {
		t.Fatalf("持有者步骤写入失败: %v", err)
	}
	sha := strings.Repeat("b", 64)
	baseArtifact := ArtifactInput{JobID: job.Job.ID, Kind: "report", URI: "synthetic://fenced/1", SHA256: sha, Bytes: 1, CreatedAt: started}
	wrongArtifact := baseArtifact
	wrongArtifact.LeaseOwner = "worker-fence/claim-old"
	if err := svc.AddArtifact(ctx, wrongArtifact); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("非持有者工件写入期望 ErrLeaseLost，得到 %v", err)
	}
	goodArtifact := baseArtifact
	goodArtifact.LeaseOwner = validOwner
	if err := svc.AddArtifact(ctx, goodArtifact); err != nil {
		t.Fatalf("持有者工件写入失败: %v", err)
	}

	// 租约过期后：旧 token 的步骤/工件/续租/推进/完成写入全部失败。
	clock.Advance(2 * time.Minute)
	expiredStep := baseStep
	expiredStep.Step = "expired-step"
	expiredStep.LeaseOwner = validOwner
	if err := svc.RecordStep(ctx, expiredStep); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("过期租约步骤写入期望 ErrLeaseLost，得到 %v", err)
	}
	expiredArtifact := baseArtifact
	expiredArtifact.URI = "synthetic://fenced/2"
	expiredArtifact.LeaseOwner = validOwner
	if err := svc.AddArtifact(ctx, expiredArtifact); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("过期租约工件写入期望 ErrLeaseLost，得到 %v", err)
	}
	if err := svc.Heartbeat(ctx, job.Job.ID, validOwner, time.Minute); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("过期租约续租期望 ErrLeaseLost，得到 %v", err)
	}
	if _, err := svc.AdvanceStatus(ctx, job.Job.ID, validOwner, StatusInventory); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("过期租约推进期望 ErrLeaseLost，得到 %v", err)
	}
	if _, err := svc.Complete(ctx, job.Job.ID, validOwner, StatusFailed, "SYNTHETIC", "expired"); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("过期租约完成期望 ErrLeaseLost，得到 %v", err)
	}

	// 新实例重领后，旧 token 不得写终态；新 token 可以正常完成。
	reclaimed, ok, err := svc.Claim(ctx, KindAssetResearch, "worker-fence/claim-2", time.Minute)
	if err != nil || !ok {
		t.Fatalf("重领失败: ok=%v err=%v", ok, err)
	}
	if _, err := svc.Complete(ctx, job.Job.ID, validOwner, StatusCompleted, "", ""); !errors.Is(err, ErrLeaseLost) {
		t.Fatalf("旧 token 完成期望 ErrLeaseLost，得到 %v", err)
	}
	done, err := svc.Complete(ctx, job.Job.ID, reclaimed.Job.LeaseOwner, StatusCompleted, "", "")
	if err != nil || done.Job.Status != StatusCompleted {
		t.Fatalf("新 token 完成失败: status=%v err=%v", done.Job.Status, err)
	}
}
