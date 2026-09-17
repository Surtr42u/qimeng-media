-- users queries (M1 single-user auth state: first-boot setup + token
-- hash verification source).
-- ASCII-only comments here; see assets.sql header note and
-- migrations/0001_init.up.sql for Chinese explanations.
--
-- The users table is multi-user-shaped on purpose (SECURITY.md); M1
-- code only ever touches the FIRST row (created_at ASC).

-- name: CountUsers :one
SELECT COUNT(*) FROM users;

-- name: GetFirstUser :one
SELECT * FROM users ORDER BY created_at LIMIT 1;

-- name: CreateUser :exec
INSERT INTO users (id, name, password_hash, role, token_hash, created_at)
VALUES (?, ?, ?, ?, ?, ?);

-- name: UpdateUserTokenHash :exec
-- DEPRECATED since 2026-09-17: the multi-session model writes auth_sessions
-- (see sessions.sql); login no longer overwrites the single users.token_hash.
-- Kept (unused) so historical generated code stays untouched.
UPDATE users SET token_hash = ? WHERE id = (SELECT id FROM users ORDER BY created_at LIMIT 1);
