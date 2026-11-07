-- ai client credentials and write audit (ADR-0057 P1).
-- ASCII-only comments (sqlc v1.31.1 multi-byte comment parser bug).
-- Timestamps are TEXT in the store-wide UTC RFC3339 millisecond format.
-- Plaintext tokens never reach this layer: only auth.TokenHash output is stored
-- and looked up.

-- name: CreateAiClient :exec
INSERT INTO ai_clients (
    id, label, token_hash, scopes_json, device_ids_json, created_at,
    expires_at, revoked_at, last_used_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);

-- name: GetAiClient :one
SELECT * FROM ai_clients WHERE id = ?;

-- name: GetAiClientByTokenHash :one
SELECT * FROM ai_clients WHERE token_hash = ?;

-- name: ListAiClients :many
SELECT * FROM ai_clients ORDER BY created_at DESC, id DESC;

-- name: RevokeAiClient :execrows
UPDATE ai_clients SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL;

-- name: RotateAiClientToken :execrows
UPDATE ai_clients SET token_hash = ? WHERE id = ? AND revoked_at IS NULL;

-- name: TouchAiClientLastUsed :exec
UPDATE ai_clients SET last_used_at = ? WHERE id = ?;

-- name: InsertAiAudit :exec
INSERT INTO ai_audit (
    client_id, method, path, action, status_code, request_id, created_at
) VALUES (?, ?, ?, ?, ?, ?, ?);
-- name: ListAiAuditByClient :many
SELECT * FROM ai_audit
WHERE client_id = ?
ORDER BY created_at DESC, id DESC
LIMIT ?;
