-- assets queries (M1 minimal set: scanner ingestion + list pagination).
--
-- NOTE (why English comments here): sqlc v1.31.1's SQLite parser has a
-- column-accounting bug with multi-byte (Chinese) comment text -- parse
-- failures trigger non-deterministically depending on comment length.
-- Chinese explanations live in migrations/0001_init.up.sql and
-- docs/adr/0004; keep comments in this directory ASCII-only.

-- UpsertAsset: the single ingestion entry point (adr/0004 identity).
-- Conflict target is the PATH (library_id, rel_path); on conflict only
-- metadata attributes are refreshed. asset_id / created_at keep their
-- first-seen values (identity is for life; ingest time frozen for the
-- freshness scoring). Change detection (size+mtime) is the scanner's
-- job before calling this.

-- name: UpsertAsset :one
INSERT INTO assets (
    asset_id, library_id, rel_path, file_name, media_type,
    size_bytes, mtime, duration_ms, width, height,
    video_codec, audio_codec, source,
    created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (library_id, rel_path) DO UPDATE SET
    file_name    = excluded.file_name,
    media_type   = excluded.media_type,
    size_bytes   = excluded.size_bytes,
    mtime        = excluded.mtime,
    duration_ms  = excluded.duration_ms,
    width        = excluded.width,
    height       = excluded.height,
    video_codec  = excluded.video_codec,
    audio_codec  = excluded.audio_codec,
    source       = excluded.source,
    updated_at   = excluded.updated_at
RETURNING *;

-- UpdatePlaybackProgress: resume-position state write (PUT /assets/{id}/progress).
-- NOT an event-stream insert: ADR-0005 untouched, only the latest value is
-- kept (rationale in migrations/0006_playback.up.sql header). Handler maps
-- RowsAffected==0 to 404 (assets rows are hard-deleted into trash, DOMAIN_RULES
-- recovery semantics). updated_at deliberately NOT bumped: progress is player
-- state, not a content change (list freshness relies on mtime/created_at only).

-- name: UpdatePlaybackProgress :execrows
UPDATE assets
SET last_position_seconds = ?
WHERE asset_id = ?;

-- name: GetAsset :one
SELECT * FROM assets WHERE asset_id = ?;

-- GetAssetByPath: used by the scanner's move-merge heuristic
-- (old path gone + new path seen + size/mtime match -> keep identity,
-- update path attribute; see adr/0004).

-- name: GetAssetByPath :one
SELECT * FROM assets WHERE library_id = ? AND rel_path = ?;

-- ListAssetsFirstPage / ListAssetsAfterCursor: keyset pagination.
-- Composite order (created_at DESC, asset_id DESC): created_at alone is
-- not unique (batch scans share the same millisecond), so pagination
-- would lose/duplicate rows; asset_id is the deterministic tiebreaker.
-- Cursor = (created_at, asset_id) of the last row of the previous page;
-- the WHERE clause is the strict expansion of the row-value comparison
-- (created_at, asset_id) < (?, ?). OFFSET is never used: deep OFFSET is
-- O(n) on NAS disks, cursor seek is O(log n).

-- name: ListAssetsFirstPage :many
SELECT * FROM assets
ORDER BY created_at DESC, asset_id DESC
LIMIT ?;

-- name: ListAssetsAfterCursor :many
SELECT * FROM assets
WHERE created_at < ? OR (created_at = ? AND asset_id < ?)
ORDER BY created_at DESC, asset_id DESC
LIMIT ?;
