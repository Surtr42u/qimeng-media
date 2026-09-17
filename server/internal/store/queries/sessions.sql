-- sessions queries (multi-device concurrent auth sessions, migration 0011).
-- ASCII-only comments here; see assets.sql header note and
-- migrations/0011_auth_sessions.up.sql for Chinese explanations.

-- name: CreateSession :exec
INSERT INTO auth_sessions (id, user_id, token_hash, device_label, created_at)
VALUES (?, ?, ?, ?, ?);

-- name: ListSessionHashesByUser :many
SELECT token_hash FROM auth_sessions WHERE user_id = ?;

-- name: DeleteSessionByTokenHash :exec
-- Logout: revoke exactly the session matching the request token's hash
-- (token_hash UNIQUE keeps it to one row).
DELETE FROM auth_sessions WHERE token_hash = ?;

-- name: CountSessionsByUser :one
SELECT COUNT(*) FROM auth_sessions WHERE user_id = ?;

-- name: ListSessionIDsByUser :many
-- Newest first (created_at DESC, id as tiebreaker because millisecond
-- timestamps can collide on rapid dev-login loops). Combined with
-- DeleteSessionByID this implements "keep newest N per user" pruning in Go:
-- sqlc v1.31.1's SQLite parser rejects the equivalent single DELETE with a
-- same-table subquery ("column user_id is ambiguous") and WITH..DELETE
-- ("relation does not exist"), so the cap logic lives in registerSessionLocked.
SELECT id FROM auth_sessions WHERE user_id = ? ORDER BY created_at DESC, id;

-- name: DeleteSessionByID :exec
DELETE FROM auth_sessions WHERE id = ?;
