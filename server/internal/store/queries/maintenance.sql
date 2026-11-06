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
SELECT * FROM maintenance_jobs
WHERE status = 'queued'
  AND (lease_until IS NULL OR lease_until < sqlc.arg(now))
ORDER BY created_at ASC, id ASC
LIMIT 1;

-- name: ClaimMaintenanceJob :execrows
UPDATE maintenance_jobs
SET lease_owner = ?,
    lease_until = ?,
    started_at = COALESCE(started_at, ?),
    updated_at = ?
WHERE id = ?
  AND status = 'queued'
  AND (lease_until IS NULL OR lease_until < ?);

-- name: HeartbeatMaintenanceJob :execrows
UPDATE maintenance_jobs
SET lease_until = ?,
    updated_at = ?
WHERE id = ?
  AND lease_owner = ?
  AND status NOT IN ('completed', 'partial', 'failed', 'cancelled');

-- name: FinishMaintenanceJob :execrows
UPDATE maintenance_jobs
SET status = ?,
    finished_at = ?,
    lease_owner = NULL,
    lease_until = NULL,
    error_code = ?,
    error_message = ?,
    updated_at = ?
WHERE id = ?
  AND lease_owner = ?
  AND status NOT IN ('completed', 'partial', 'failed', 'cancelled');

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
