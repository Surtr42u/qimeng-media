-- maintenance queries: persistent job ledger (migration 0023).
-- ASCII-only comments (sqlc v1.31.1 multi-byte comment parser bug).
-- Timestamps are TEXT in the store-wide UTC RFC3339 millisecond format.

-- name: CreateMaintenanceJob :exec
INSERT INTO maintenance_jobs (
    id, kind, status, library_id, payload_json, input_fingerprint,
    rules_version, authorization_scope, budget_json, idempotency_key,
    lease_owner, lease_until, created_at, updated_at, started_at,
    finished_at, error_code, error_message
) VALUES (
    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
);

-- name: GetMaintenanceJob :one
SELECT * FROM maintenance_jobs WHERE id = ?;

-- name: GetMaintenanceJobByIdempotencyKey :one
SELECT * FROM maintenance_jobs WHERE idempotency_key = ?;

-- name: ListMaintenanceJobs :many
SELECT
    j.*,
    (SELECT COUNT(*) FROM maintenance_steps s
     WHERE s.job_id = j.id) AS step_count,
    (SELECT COUNT(*) FROM maintenance_steps s
     WHERE s.job_id = j.id AND s.status = 'completed') AS completed_steps
FROM maintenance_jobs j
WHERE (sqlc.narg(status) IS NULL OR j.status = sqlc.narg(status))
  AND (sqlc.narg(cursor_created_at) IS NULL
       OR j.created_at < sqlc.narg(cursor_created_at)
       OR (j.created_at = sqlc.narg(cursor_created_at)
           AND j.id < sqlc.narg(cursor_id)))
ORDER BY j.created_at DESC, j.id DESC
LIMIT sqlc.arg(row_limit);

-- name: ListMaintenanceSteps :many
SELECT * FROM maintenance_steps
WHERE job_id = ?
ORDER BY started_at DESC, step ASC
LIMIT ?;

-- name: ListMaintenanceArtifacts :many
SELECT * FROM maintenance_artifacts
WHERE job_id = ?
ORDER BY created_at DESC, id DESC
LIMIT ?;

-- name: PauseMaintenanceJob :execrows
UPDATE maintenance_jobs
SET status = 'paused',
    lease_owner = NULL,
    lease_until = NULL,
    updated_at = ?
WHERE id = ?
  AND status NOT IN ('completed', 'partial', 'failed', 'cancelled');

-- name: ResumeMaintenanceJob :execrows
UPDATE maintenance_jobs
SET status = 'queued',
    lease_owner = NULL,
    lease_until = NULL,
    error_code = NULL,
    error_message = NULL,
    updated_at = ?
WHERE id = ?
  AND status = 'paused';

-- name: CancelMaintenanceJob :execrows
UPDATE maintenance_jobs
SET status = 'cancelled',
    lease_owner = NULL,
    lease_until = NULL,
    finished_at = ?,
    updated_at = ?
WHERE id = ?
  AND status NOT IN ('completed', 'partial', 'failed', 'cancelled');

-- name: ListClaimableMaintenanceJobs :many
-- Claim scan for the in-process worker: restrict to the kind the caller has a
-- registered handler for (unregistered kinds must stay queued for external
-- executors), and include in-flight states whose lease expired so a crashed or
-- restarted worker can take the job back over and resume from completed steps.
-- waiting_*/paused/terminal states are deliberately excluded: only external
-- evidence or the public pause/resume endpoints re-queue them.
SELECT * FROM maintenance_jobs
WHERE kind = sqlc.arg(kind)
  AND status IN ('queued', 'inventory', 'research', 'planned', 'applying', 'verifying')
  AND (lease_until IS NULL OR lease_until < sqlc.arg(now))
ORDER BY created_at ASC, id ASC
LIMIT 1;

-- name: ClaimMaintenanceJob :execrows
-- Same status list as ListClaimableMaintenanceJobs (keep the two in sync):
-- taking over an expired in-flight job preserves its status so the worker can
-- continue the pipeline instead of restarting side effects.
UPDATE maintenance_jobs
SET lease_owner = ?,
    lease_until = ?,
    started_at = COALESCE(started_at, ?),
    updated_at = ?
WHERE id = ?
  AND status IN ('queued', 'inventory', 'research', 'planned', 'applying', 'verifying')
  AND (lease_until IS NULL OR lease_until < ?);

-- name: AdvanceMaintenanceJobStatus :execrows
-- Worker-facing pipeline advance. The previous status is part of the WHERE
-- clause so a concurrent pause/resume/cancel (or a second worker) cannot be
-- overwritten; lease_owner + an unexpired lease_until prove the caller still
-- holds the job (an expired lease is recoverable by another claim).
UPDATE maintenance_jobs
SET status = sqlc.arg(next_status),
    updated_at = sqlc.arg(updated_at)
WHERE id = sqlc.arg(id)
  AND lease_owner = sqlc.arg(lease_owner)
  AND status = sqlc.arg(current_status)
  AND lease_until IS NOT NULL
  AND lease_until >= sqlc.arg(now);

-- name: ReleaseMaintenanceLease :execrows
-- Best-effort release for a claim that cannot be used locally (e.g. the detail
-- read after the claim UPDATE failed). Only the current holder may release, so
-- a delayed cleanup can never steal a lease that was already reassigned.
UPDATE maintenance_jobs
SET lease_owner = NULL,
    lease_until = NULL,
    updated_at = sqlc.arg(updated_at)
WHERE id = sqlc.arg(id)
  AND lease_owner = sqlc.arg(lease_owner);

-- name: HeartbeatMaintenanceJob :execrows
-- Only the current holder of a still-valid lease may renew it. An expired lease
-- is no longer authoritative: the job may already be claimable by another
-- worker, so a stale executor must not be able to revive its ownership.
UPDATE maintenance_jobs
SET lease_until = sqlc.arg(lease_until),
    updated_at = sqlc.arg(updated_at)
WHERE id = sqlc.arg(id)
  AND lease_owner = sqlc.arg(lease_owner)
  AND status NOT IN ('completed', 'partial', 'failed', 'cancelled')
  AND lease_until IS NOT NULL
  AND lease_until >= sqlc.arg(now);

-- name: FinishMaintenanceJob :execrows
-- Finishing is fenced by owner + unexpired lease: a worker whose lease expired
-- and was (or can be) reassigned must not write the terminal state.
UPDATE maintenance_jobs
SET status = sqlc.arg(status),
    finished_at = sqlc.arg(finished_at),
    lease_owner = NULL,
    lease_until = NULL,
    error_code = sqlc.arg(error_code),
    error_message = sqlc.arg(error_message),
    updated_at = sqlc.arg(updated_at)
WHERE id = sqlc.arg(id)
  AND lease_owner = sqlc.arg(lease_owner)
  AND status NOT IN ('completed', 'partial', 'failed', 'cancelled')
  AND lease_until IS NOT NULL
  AND lease_until >= sqlc.arg(now);

-- name: UpsertMaintenanceStep :exec
INSERT INTO maintenance_steps (
    job_id, step, status, attempt, started_at, finished_at, detail_json
) VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(job_id, step) DO UPDATE SET
    status = excluded.status,
    attempt = excluded.attempt,
    started_at = excluded.started_at,
    finished_at = excluded.finished_at,
    detail_json = excluded.detail_json;

-- name: InsertMaintenanceArtifact :exec
INSERT INTO maintenance_artifacts (
    job_id, kind, uri, sha256, bytes, created_at
) VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT(job_id, kind, uri) DO NOTHING;

-- name: CountMaintenanceStepsByJob :one
SELECT COUNT(*) AS step_count,
       COUNT(CASE WHEN status = 'completed' THEN 1 END) AS completed_steps
FROM maintenance_steps
WHERE job_id = ?;

-- name: ListMaintenanceCompletedStepNames :many
-- Full (unpaginated) list of step names already completed or skipped. The
-- executor claim response and the recovery path use it to skip side effects;
-- the detail endpoint intentionally caps steps at 50, so worker recovery must
-- not rely on Detail.Steps.
SELECT step FROM maintenance_steps
WHERE job_id = ? AND status IN ('completed', 'skipped')
ORDER BY step;

-- name: GetMaintenanceStep :one
SELECT * FROM maintenance_steps WHERE job_id = ? AND step = ?;

-- name: ListMaintenanceSettledSteps :many
-- Settled steps with their real status (completed vs skipped). Recovery must
-- preserve skipped: treating it as completed would turn a partial job into
-- completed after a re-claim, and the detail endpoint's 50-step window hides
-- older settled steps entirely.
SELECT step, status FROM maintenance_steps
WHERE job_id = ? AND status IN ('completed', 'skipped')
ORDER BY step;
