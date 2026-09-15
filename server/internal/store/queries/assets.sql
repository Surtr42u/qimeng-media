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
-- cos_work (migration 0008): COS work directory name, written only by the
-- COS ingest path; normal libraries pass NULL (author/work isolation,
-- DOMAIN_RULES 6). Refreshed on conflict like source -- a renamed work
-- directory must re-point the asset.
INSERT INTO assets (
    asset_id, library_id, rel_path, file_name, media_type,
    size_bytes, mtime, duration_ms, width, height,
    video_codec, audio_codec, source, cos_work,
    created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
    cos_work     = excluded.cos_work,
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

-- name: ListCosWorkForAssets :many
-- Batch cos_work lookup for list endpoints (AssetSummary.cosWork): the
-- work subfolder name (rel_path second segment, migration 0008) of the
-- given assets, one row per asset that has one. Only kind=cos library
-- scans assign cos_work, so regular assets never match the IS NOT NULL
-- filter and stay absent from the result (null on the wire = client
-- falls back to fileName for the card title). asset_ids_json is a JSON
-- array consumed by json_each -- same parameter shape as
-- ListAuthorNamesForAssets (see browse.sql header for the sqlc parser
-- constraints that dictate it).
SELECT a.asset_id, a.cos_work
FROM assets a
WHERE a.cos_work IS NOT NULL
  AND a.asset_id IN (SELECT value FROM json_each(sqlc.narg(asset_ids_json)));

-- name: CountEnabledLibraryAssets :one
-- Thumbnail coverage progress denominator (2026-09-15 batch). Same WHERE as
-- ListThumbnailWarmup candidates; keep both in sync.
SELECT COUNT(*) AS total FROM assets a
JOIN libraries l ON l.id = a.library_id
WHERE l.enabled = 1;

-- name: ListThumbnailWarmup :many
-- Warmup candidates for automatic thumbnail pre-generation (2026-09-15 batch,
-- mirrors the legacy app "thumbs exist right after scan" experience): all
-- assets of enabled libraries + library root + media type. "Which thumbnails
-- are missing" is decided in Go (cache key = SHA-256, see cachekey.go; SQL
-- cannot express it) via os.Stat on the thumb destination path.
SELECT a.asset_id, l.root_path, a.rel_path, a.media_type
FROM assets a
JOIN libraries l ON l.id = a.library_id
WHERE l.enabled = 1
ORDER BY a.created_at ASC, a.asset_id ASC;
