-- device apply queries: PC -> phone package ledger (migration 0024).
-- ASCII-only comments (sqlc v1.31.1 multi-byte comment parser bug).
-- Timestamps are TEXT in the store-wide UTC RFC3339 millisecond format.

-- name: CreateDeviceApplyTask :exec
INSERT INTO device_apply_tasks (
    id, target_device_id, status, protocol_version, rules_fingerprint,
    manifest_json, created_at, updated_at, claimed_at, finished_at,
    expires_at, result_json
) VALUES (
    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
);

-- name: GetDeviceApplyTask :one
SELECT * FROM device_apply_tasks WHERE id = ?;

-- name: ListDeviceApplyTasks :many
SELECT
    t.*,
    (SELECT COUNT(*) FROM device_apply_segments s
     WHERE s.task_id = t.id) AS segment_count
FROM device_apply_tasks t
WHERE t.target_device_id = sqlc.arg(target_device_id)
  AND (sqlc.narg(status) IS NULL OR t.status = sqlc.narg(status))
  AND (sqlc.narg(cursor_created_at) IS NULL
       OR t.created_at < sqlc.narg(cursor_created_at)
       OR (t.created_at = sqlc.narg(cursor_created_at)
           AND t.id < sqlc.narg(cursor_id)))
ORDER BY t.created_at DESC, t.id DESC
LIMIT sqlc.arg(row_limit);

-- name: ClaimDeviceApplyTask :execrows
UPDATE device_apply_tasks
SET status = 'claimed',
    claimed_at = COALESCE(claimed_at, ?),
    updated_at = ?
WHERE id = ?
  AND status = 'pending'
  AND expires_at > ?;

-- name: ExpireDeviceApplyTask :execrows
UPDATE device_apply_tasks
SET status = 'expired',
    finished_at = ?,
    updated_at = ?
WHERE id = ?
  AND status IN ('pending', 'claimed')
  AND expires_at <= ?;

-- name: ExpireDeviceApplyTasks :execrows
UPDATE device_apply_tasks
SET status = 'expired',
    finished_at = ?,
    updated_at = ?
WHERE status IN ('pending', 'claimed')
  AND expires_at <= ?;

-- name: FinishDeviceApplyTask :execrows
UPDATE device_apply_tasks
SET status = ?,
    result_json = ?,
    verification_json = ?,
    finished_at = ?,
    updated_at = ?
WHERE id = ?
  AND status = 'claimed';

-- name: CancelDeviceApplyTask :execrows
UPDATE device_apply_tasks
SET status = 'cancelled',
    finished_at = ?,
    updated_at = ?
WHERE id = ?
  AND status IN ('pending', 'claimed');

-- name: CreateDeviceApplySegment :exec
INSERT INTO device_apply_segments (
    task_id, kind, bytes, sha256, rel_path
) VALUES (?, ?, ?, ?, ?);

-- name: ListDeviceApplySegments :many
SELECT * FROM device_apply_segments
WHERE task_id = ?
ORDER BY CASE kind WHEN 'masterTxt' THEN 0 ELSE 1 END;

-- name: GetDeviceApplySegment :one
SELECT * FROM device_apply_segments
WHERE task_id = ? AND kind = ?;

-- name: GetDeviceApplyAuthoritativeState :one
-- Absolute business-state counts read from the same database at result time.
-- These are authoritative (DB truth), unlike the incremental counters in the
-- import response; they are stored in verification_json for later comparison.
SELECT
    (SELECT COUNT(*) FROM assets) AS assets,
    (SELECT COUNT(*) FROM authors) AS authors,
    (SELECT COUNT(*) FROM asset_authors) AS asset_author_refs,
    (SELECT COUNT(*) FROM tags) AS tags,
    (SELECT COUNT(*) FROM asset_tags) AS asset_tag_refs,
    (SELECT COUNT(*) FROM view_events) AS view_events;
