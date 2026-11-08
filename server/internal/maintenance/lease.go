package maintenance

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// Claim atomically leases the oldest claimable job of kind whose lease is
// absent or expired. It is an internal worker primitive: this package exposes
// no HTTP claim endpoint. ok=false means there was nothing to claim.
//
// owner is the fencing token of this claim, not a long-lived instance name: the
// worker appends a fresh random token per claim (Worker.newLeaseToken). Reusing
// one owner string across claims (or instances) would make an expired claim and
// its re-claim indistinguishable, allowing the old executor to keep renewing
// and overwrite the new executor's steps. Callers must therefore pass a token
// that is unique per claim attempt.
//
// Reclaiming an in-flight status (inventory/research/planned/applying/
// verifying) whose lease expired is how a restarted worker takes a job back
// over; the worker skips already-completed steps so no side effect repeats.
func (s *Service) Claim(ctx context.Context, kind Kind, owner string, ttl time.Duration) (Detail, bool, error) {
	return s.claim(ctx, kind, owner, ttl, s.now().UTC())
}

// claim is Claim with an explicit clock value. The external executor response
// must report the exact leaseUntil committed by the same UPDATE; recomputing
// the deadline afterwards would drift by a few milliseconds.
func (s *Service) claim(ctx context.Context, kind Kind, owner string, ttl time.Duration, now time.Time) (Detail, bool, error) {
	kind = Kind(strings.TrimSpace(string(kind)))
	if !kind.Valid() {
		return Detail{}, false, fmt.Errorf("%w: kind 不合法", ErrInvalidInput)
	}
	owner = strings.TrimSpace(owner)
	if owner == "" || !checkRunes(owner, MaxLeaseOwnerRunes) || ttl <= 0 {
		return Detail{}, false, fmt.Errorf("%w: 租约 owner/ttl 不合法", ErrInvalidInput)
	}
	now = now.UTC()
	nowText := store.FormatTimestamp(now)
	untilText := store.FormatTimestamp(now.Add(ttl))
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Detail{}, false, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	rows, err := q.ListClaimableMaintenanceJobs(ctx, db.ListClaimableMaintenanceJobsParams{
		Kind: string(kind),
		Now:  sql.NullString{String: nowText, Valid: true},
	})
	if err != nil {
		return Detail{}, false, err
	}
	if len(rows) == 0 {
		return Detail{}, false, nil
	}
	candidate := rows[0]
	// 条件更新是领取的原子点：只有 status 可领取且租约仍为空/过期时才真正
	// 领取；affected==0 表示扫描与更新之间被其它 worker 抢先，返回 false 让
	// 调用方下轮重试，绝不把它当成已领取。
	affected, err := q.ClaimMaintenanceJob(ctx, db.ClaimMaintenanceJobParams{
		LeaseOwner:   sql.NullString{String: owner, Valid: true},
		LeaseUntil:   sql.NullString{String: untilText, Valid: true},
		StartedAt:    sql.NullString{String: nowText, Valid: true},
		UpdatedAt:    nowText,
		ID:           candidate.ID,
		LeaseUntil_2: sql.NullString{String: nowText, Valid: true},
	})
	if err != nil {
		return Detail{}, false, err
	}
	if affected == 0 {
		// Another worker won the lease between the scan and the update.
		return Detail{}, false, nil
	}
	if err := tx.Commit(); err != nil {
		return Detail{}, false, err
	}
	detail, err := s.Get(ctx, candidate.ID)
	if err != nil {
		// P3-3：租约已在事务里提交，但详情读取失败（临时 DB/ctx 错误）时
		// worker 拿不到已提交步骤，无法安全续跑。释放本次租约，避免任务被
		// 锁到 TTL 后才可重领；释放失败也只记日志（TTL 兜底）。
		s.releaseLease(candidate.ID, owner)
		return Detail{}, false, err
	}
	return detail, true, nil
}

// releaseLease 尽力释放 owner 名下的租约（Claim 成功后本地准备失败等清理
// 路径）。用独立的 Background 上下文而不是调用方 ctx：调用方 ctx 可能已
// 取消，但释放属于收尾语义，仍应尝试；失败由 TTL 自然过期兜底。
func (s *Service) releaseLease(id, owner string) {
	ctx := context.Background()
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		s.logger.Warn("释放维护任务租约失败（等待 TTL 自然过期）", "jobId", id, "error", err)
		return
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	affected, err := q.ReleaseMaintenanceLease(ctx, db.ReleaseMaintenanceLeaseParams{
		UpdatedAt:  store.FormatTimestamp(s.now().UTC()),
		ID:         id,
		LeaseOwner: sql.NullString{String: owner, Valid: true},
	})
	if err != nil {
		s.logger.Warn("释放维护任务租约失败（等待 TTL 自然过期）", "jobId", id, "error", err)
		return
	}
	if affected == 0 {
		// 租约已被暂停/取消/重领清理：无需释放。
		return
	}
	if err := tx.Commit(); err != nil {
		s.logger.Warn("提交维护任务租约释放失败（等待 TTL 自然过期）", "jobId", id, "error", err)
	}
}

// Heartbeat extends a lease held by owner. ErrLeaseLost means the worker no
// longer owns the job (expired, reassigned, cancelled or terminal).
//
// 未过期是硬条件：租约一旦过期，该 owner 就不再是权威持有者（任务已可被
// 其它 worker 重领），因此旧执行器不能靠续租"复活"租约。正常执行期心跳
// 间隔为 TTL/3，远早于过期；若单次续租失败超过 TTL，worker 会停止并由
// 重领路径接续（completed 步骤不会重复副作用）。
func (s *Service) Heartbeat(ctx context.Context, id, owner string, ttl time.Duration) error {
	_, err := s.heartbeat(ctx, id, owner, ttl)
	return err
}

// heartbeat is Heartbeat returning the exact renewed deadline for the external
// executor response.
func (s *Service) heartbeat(ctx context.Context, id, owner string, ttl time.Duration) (time.Time, error) {
	id, err := normalizeID(id)
	if err != nil {
		return time.Time{}, err
	}
	owner = strings.TrimSpace(owner)
	if owner == "" || !checkRunes(owner, MaxLeaseOwnerRunes) || ttl <= 0 {
		return time.Time{}, fmt.Errorf("%w: 租约 owner/ttl 不合法", ErrInvalidInput)
	}
	now := s.now().UTC()
	nowText := store.FormatTimestamp(now)
	affected, err := s.q.HeartbeatMaintenanceJob(ctx, db.HeartbeatMaintenanceJobParams{
		LeaseUntil: sql.NullString{String: store.FormatTimestamp(now.Add(ttl)), Valid: true},
		UpdatedAt:  nowText,
		ID:         id,
		LeaseOwner: sql.NullString{String: owner, Valid: true},
		Now:        sql.NullString{String: nowText, Valid: true},
	})
	if err != nil {
		return time.Time{}, err
	}
	if affected == 0 {
		return time.Time{}, ErrLeaseLost
	}
	return now.Add(ttl), nil
}

// Complete finishes a job owned by owner. Only completed/partial/failed are
// accepted; completed always clears the error fields. It is an internal worker
// primitive with no HTTP endpoint in this slice.
//
// 终态写入同样受 owner + 未过期租约围栏：租约过期后任务已可被重领，旧执行
// 器不得把结果写成终态覆盖新执行器。
func (s *Service) Complete(ctx context.Context, id, owner string, status Status, errorCode, errorMessage string) (Detail, error) {
	id, err := normalizeID(id)
	if err != nil {
		return Detail{}, err
	}
	owner = strings.TrimSpace(owner)
	if owner == "" || !checkRunes(owner, MaxLeaseOwnerRunes) {
		return Detail{}, fmt.Errorf("%w: 租约 owner 不合法", ErrInvalidInput)
	}
	switch status {
	case StatusCompleted, StatusPartial, StatusFailed:
	default:
		return Detail{}, fmt.Errorf("%w: 终态须为 completed/partial/failed", ErrInvalidInput)
	}
	errorCode = strings.TrimSpace(errorCode)
	errorMessage = strings.TrimSpace(errorMessage)
	if !checkRunes(errorCode, MaxErrorCodeRunes) || !checkRunes(errorMessage, MaxErrorMessageRunes) {
		return Detail{}, fmt.Errorf("%w: errorCode/errorMessage 过长", ErrInvalidInput)
	}
	if status == StatusCompleted {
		errorCode = ""
		errorMessage = ""
	}
	now := s.now().UTC()
	nowText := store.FormatTimestamp(now)
	affected, err := s.q.FinishMaintenanceJob(ctx, db.FinishMaintenanceJobParams{
		Status:       string(status),
		FinishedAt:   sql.NullString{String: nowText, Valid: true},
		ErrorCode:    nullableString(errorCode),
		ErrorMessage: nullableString(errorMessage),
		UpdatedAt:    nowText,
		ID:           id,
		LeaseOwner:   sql.NullString{String: owner, Valid: true},
		Now:          sql.NullString{String: nowText, Valid: true},
	})
	if err != nil {
		return Detail{}, err
	}
	if affected == 0 {
		return Detail{}, ErrLeaseLost
	}
	return s.Get(ctx, id)
}

// AdvanceStatus is the worker-facing pipeline advance: it moves a job owned by
// owner to next, validating the worker-facing state machine (state.go) and the
// lease owner in the same transaction/UPDATE. A same-state call is a no-op,
// which lets a recovered worker re-enter the pipeline at whatever stage the
// ledger recorded. It is an internal primitive with no HTTP endpoint; the
// public pause/resume/cancel transitions stay in service.go.
//
// owner 必须是本次领取的 fencing token，且租约未过期；租约过期后任务可被
// 重领，旧执行器不得再推进状态。
func (s *Service) AdvanceStatus(ctx context.Context, id, owner string, next Status) (Detail, error) {
	id, err := normalizeID(id)
	if err != nil {
		return Detail{}, err
	}
	owner = strings.TrimSpace(owner)
	if owner == "" || !checkRunes(owner, MaxLeaseOwnerRunes) {
		return Detail{}, fmt.Errorf("%w: 租约 owner 不合法", ErrInvalidInput)
	}
	if !next.Valid() {
		return Detail{}, fmt.Errorf("%w: 目标状态不合法", ErrInvalidInput)
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Detail{}, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	row, err := getJobTx(ctx, q, id)
	if err != nil {
		return Detail{}, err
	}
	now := s.now().UTC()
	if !leaseStillValidAt(row, owner, now) {
		return Detail{}, ErrLeaseLost
	}
	if Status(row.Status) == next {
		// 状态已到位：释放读事务后再回读（store 用 _txlock=immediate）。
		s.rollback(tx)
		return s.Get(ctx, id)
	}
	if !Status(row.Status).CanTransitionTo(next) {
		return Detail{}, ErrStateConflict
	}
	affected, err := q.AdvanceMaintenanceJobStatus(ctx, db.AdvanceMaintenanceJobStatusParams{
		NextStatus:    string(next),
		UpdatedAt:     store.FormatTimestamp(now),
		ID:            id,
		LeaseOwner:    sql.NullString{String: owner, Valid: true},
		CurrentStatus: row.Status,
		Now:           sql.NullString{String: store.FormatTimestamp(now), Valid: true},
	})
	if err != nil {
		return Detail{}, err
	}
	if affected == 0 {
		// 读后写之间被暂停/取消或另一 worker 接管：当前持有者已无权推进。
		return Detail{}, ErrLeaseLost
	}
	if err := tx.Commit(); err != nil {
		return Detail{}, err
	}
	return s.Get(ctx, id)
}

// leaseStillValidAt 判断账本行的租约是否仍属于 owner 且未过期。空租约与
// 过期租约一律视为失效：过期后任务可被其它 worker 重领，旧执行器不得再写
// 步骤/工件/状态/终态（fencing 的 Go 侧读校验；SQL 更新条件再兜一层）。
func leaseStillValidAt(row db.MaintenanceJob, owner string, now time.Time) bool {
	if !row.LeaseOwner.Valid || row.LeaseOwner.String != owner {
		return false
	}
	until := parseNullTimestamp(row.LeaseUntil)
	return until != nil && !until.Before(now)
}
