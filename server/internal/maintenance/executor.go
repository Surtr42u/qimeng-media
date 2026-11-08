package maintenance

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store/db"
)

// 外部执行器受控领取的默认租约（与 worker 的 2 分钟独立：执行器可能是
// 跨进程/跨网络的 AI 运行时，默认给短租约并要求心跳续租，失败后一个 TTL
// 内即可被重领）。上限防止客户端把任务长期锁死。
const (
	// DefaultExecutorLeaseTTL 是领取/续租未指定时的默认值。
	DefaultExecutorLeaseTTL = 120 * time.Second
	// MinExecutorLeaseTTL 是允许的最小租约。
	MinExecutorLeaseTTL = 30 * time.Second
	// MaxExecutorLeaseTTL 是允许的最大租约（30 分钟）。
	MaxExecutorLeaseTTL = 30 * time.Minute
	// executorOwnerPrefix 是服务端生成 fencing token 的前缀；executor 不可
	// 自选 owner，避免两个执行器用同一字符串冒充彼此。
	executorOwnerPrefix = "executor/"
)

// LeaseResult 是一次外部执行器领取的结果。
type LeaseResult struct {
	Detail         Detail
	LeaseToken     string
	LeaseUntil     time.Time
	CompletedSteps []string
}

// NormalizeExecutorLeaseTTL 校验外部请求的租约时长：0 用默认值，越界拒绝。
func NormalizeExecutorLeaseTTL(ttl time.Duration) (time.Duration, error) {
	if ttl == 0 {
		return DefaultExecutorLeaseTTL, nil
	}
	if ttl < MinExecutorLeaseTTL || ttl > MaxExecutorLeaseTTL {
		return 0, fmt.Errorf("%w: leaseSeconds 须在 %d–%d 之间",
			ErrInvalidInput, int(MinExecutorLeaseTTL/time.Second), int(MaxExecutorLeaseTTL/time.Second))
	}
	return ttl, nil
}

// ClaimForExecutor atomically leases the oldest claimable job of kind for an
// external executor and returns a server-generated fencing token.
//
// The token is unique per claim (never supplied by the client): pause/cancel/
// terminal state or lease expiry invalidates it, so an old executor cannot
// keep renewing or write steps after the job was re-claimed. CompletedSteps is
// the full ledger list (not the 50-step detail window) so a recovered executor
// can skip side effects that already happened.
func (s *Service) ClaimForExecutor(ctx context.Context, kind Kind, ttl time.Duration) (LeaseResult, bool, error) {
	ttl, err := NormalizeExecutorLeaseTTL(ttl)
	if err != nil {
		return LeaseResult{}, false, err
	}
	now := s.now().UTC()
	owner := executorOwnerPrefix + uuid.NewString()
	detail, ok, err := s.claim(ctx, kind, owner, ttl, now)
	if err != nil || !ok {
		return LeaseResult{}, false, err
	}
	completed, err := s.CompletedStepNames(ctx, detail.Job.ID)
	if err != nil {
		// 领取已提交但读不到完整步骤集合：释放租约，避免执行器在信息不全
		// 时重复副作用（与 internal Claim 的 releaseLease 同款兜底）。
		s.releaseLease(detail.Job.ID, owner)
		return LeaseResult{}, false, err
	}
	return LeaseResult{
		Detail:         detail,
		LeaseToken:     owner,
		LeaseUntil:     now.Add(ttl),
		CompletedSteps: completed,
	}, true, nil
}

// HeartbeatForExecutor renews an external lease and returns the new deadline.
// ErrLeaseLost means the token is no longer authoritative.
func (s *Service) HeartbeatForExecutor(ctx context.Context, id, leaseToken string, ttl time.Duration) (time.Time, error) {
	ttl, err := NormalizeExecutorLeaseTTL(ttl)
	if err != nil {
		return time.Time{}, err
	}
	return s.heartbeat(ctx, id, leaseToken, ttl)
}

// CompletedStepNames returns every completed/skipped step name for a job,
// ordered by step. It is the recovery source of truth: the detail endpoint
// caps steps at MaxDetailSteps, so long-running jobs must not rely on it to
// decide which side effects already happened.
func (s *Service) CompletedStepNames(ctx context.Context, id string) ([]string, error) {
	id, err := normalizeID(id)
	if err != nil {
		return nil, err
	}
	names, err := s.q.ListMaintenanceCompletedStepNames(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("maintenance: 查询已完成步骤: %w", err)
	}
	if names == nil {
		names = []string{}
	}
	return names, nil
}

// IsLeaseLost reports whether err means the caller no longer owns the lease.
func IsLeaseLost(err error) bool { return errors.Is(err, ErrLeaseLost) }

// RecordExecutorStep upserts one executor-reported step. A finishing update
// (completed/failed/skipped) preserves the started_at recorded by an earlier
// running update: the upsert overwrites every column, so the merge has to read
// the prior row first. The write itself still goes through the fenced
// RecordStep path.
func (s *Service) RecordExecutorStep(ctx context.Context, in StepInput) error {
	jobID, err := normalizeID(in.JobID)
	if err != nil {
		return err
	}
	step := strings.TrimSpace(in.Step)
	if step == "" || !checkRunes(step, MaxStepNameRunes) {
		return fmt.Errorf("%w: step 须为 1–%d 字符", ErrInvalidInput, MaxStepNameRunes)
	}
	if !in.Status.Valid() {
		return fmt.Errorf("%w: step status 不合法", ErrInvalidInput)
	}
	prior, err := s.q.GetMaintenanceStep(ctx, db.GetMaintenanceStepParams{JobID: jobID, Step: step})
	switch {
	case errors.Is(err, sql.ErrNoRows):
		// 首次上报：无历史 started_at 可继承。
	case err != nil:
		return fmt.Errorf("maintenance: 读取既有步骤: %w", err)
	default:
		if in.StartedAt == nil {
			in.StartedAt = parseNullTimestamp(prior.StartedAt)
		}
		if in.Attempt == 0 {
			in.Attempt = int(prior.Attempt)
		}
	}
	now := s.now().UTC()
	in.JobID = jobID
	in.Step = step
	switch in.Status {
	case StepRunning:
		if in.StartedAt == nil {
			in.StartedAt = &now
		}
		in.FinishedAt = nil
	case StepCompleted, StepFailed, StepSkipped:
		if in.StartedAt == nil {
			in.StartedAt = &now
		}
		in.FinishedAt = &now
	}
	return s.RecordStep(ctx, in)
}
