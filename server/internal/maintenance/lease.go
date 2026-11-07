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
// Reclaiming an in-flight status (inventory/research/planned/applying/
// verifying) whose lease expired is how a restarted worker takes a job back
// over; the worker skips already-completed steps so no side effect repeats.
func (s *Service) Claim(ctx context.Context, kind Kind, owner string, ttl time.Duration) (Detail, bool, error) {
	kind = Kind(strings.TrimSpace(string(kind)))
	if !kind.Valid() {
		return Detail{}, false, fmt.Errorf("%w: kind 不合法", ErrInvalidInput)
	}
	owner = strings.TrimSpace(owner)
	if owner == "" || !checkRunes(owner, MaxLeaseOwnerRunes) || ttl <= 0 {
		return Detail{}, false, fmt.Errorf("%w: 租约 owner/ttl 不合法", ErrInvalidInput)
	}
	now := s.now().UTC()
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
	return detail, true, err
}

// Heartbeat extends a lease held by owner. ErrLeaseLost means the worker no
// longer owns the job (expired, reassigned, cancelled or terminal).
func (s *Service) Heartbeat(ctx context.Context, id, owner string, ttl time.Duration) error {
	id, err := normalizeID(id)
	if err != nil {
		return err
	}
	owner = strings.TrimSpace(owner)
	if owner == "" || !checkRunes(owner, MaxLeaseOwnerRunes) || ttl <= 0 {
		return fmt.Errorf("%w: 租约 owner/ttl 不合法", ErrInvalidInput)
	}
	now := s.now().UTC()
	affected, err := s.q.HeartbeatMaintenanceJob(ctx, db.HeartbeatMaintenanceJobParams{
		LeaseUntil: sql.NullString{String: store.FormatTimestamp(now.Add(ttl)), Valid: true},
		UpdatedAt:  store.FormatTimestamp(now),
		ID:         id,
		LeaseOwner: sql.NullString{String: owner, Valid: true},
	})
	if err != nil {
		return err
	}
	if affected == 0 {
		return ErrLeaseLost
	}
	return nil
}

// Complete finishes a job owned by owner. Only completed/partial/failed are
// accepted; completed always clears the error fields. It is an internal worker
// primitive with no HTTP endpoint in this slice.
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
	affected, err := s.q.FinishMaintenanceJob(ctx, db.FinishMaintenanceJobParams{
		Status:       string(status),
		FinishedAt:   sql.NullString{String: store.FormatTimestamp(now), Valid: true},
		ErrorCode:    nullableString(errorCode),
		ErrorMessage: nullableString(errorMessage),
		UpdatedAt:    store.FormatTimestamp(now),
		ID:           id,
		LeaseOwner:   sql.NullString{String: owner, Valid: true},
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
	if !row.LeaseOwner.Valid || row.LeaseOwner.String != owner {
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
	now := s.now().UTC()
	affected, err := q.AdvanceMaintenanceJobStatus(ctx, db.AdvanceMaintenanceJobStatusParams{
		Status:     string(next),
		UpdatedAt:  store.FormatTimestamp(now),
		ID:         id,
		LeaseOwner: sql.NullString{String: owner, Valid: true},
		Status_2:   row.Status,
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
