-- settings queries: server-level KV settings (migrations/0003_settings_kv).
-- Value semantics live on the reader side (JSON documents, e.g.
-- recommend_prefs); this layer is key-value only.
-- ASCII-only comments here; see assets.sql header note.

-- name: GetSetting :one
SELECT value FROM kv_settings WHERE key = ?;

-- name: UpsertSetting :exec
INSERT INTO kv_settings (key, value, updated_at)
VALUES (?, ?, ?)
ON CONFLICT (key) DO UPDATE SET
    value = excluded.value,
    updated_at = excluded.updated_at;
