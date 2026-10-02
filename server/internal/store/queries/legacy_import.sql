-- legacy_import.sql: one-shot import channel for qimeng_backup.json
-- (DOMAIN_RULES S10 mapping). Kept separate from business queries so the
-- migration semantics (idempotent upsert by legacy id) never mix with
-- runtime creation paths (uuid ids). Author writes here are named
-- Import* on purpose: the business-side authors.sql (M3 author system)
-- owns differently-named queries for the same tables.

-- name: ListAssetsByFileName :many
-- Match legacy files to in-library assets by exact file name (S10).
-- Multiple hits (same name in several folders) are disambiguated in Go
-- by rel_path directory segment == legacy folderName.
SELECT asset_id, library_id, rel_path, file_name
FROM assets
WHERE file_name = ?
ORDER BY created_at;

-- name: ImportUpsertAuthor :exec
-- Upsert by legacy authorId (cos_ prefix preserved as-is, S10); id is
-- the legacy identity, so re-import never duplicates. type derives from
-- the cos_ prefix (0001 CHECK regular|cos).
-- origin (ADR-0032): the transferred provenance is normalized by the
-- caller (store.NormalizeOrigin, fallback 'import'); the conflict branch
-- heals only untraceable rows (legacy -> transferred value) and never
-- overwrites a known one (DOMAIN_RULES 10 heal/keep rules).
INSERT INTO authors (id, display_name, type, followed, created_at, origin)
VALUES (?, ?, ?, 0, ?, ?)
ON CONFLICT(id) DO UPDATE SET display_name = excluded.display_name,
    origin = CASE WHEN authors.origin = 'legacy' THEN excluded.origin ELSE authors.origin END;

-- name: ImportMarkAuthorFollowed :execrows
-- Mark followed (S6 boolean); 0 rows affected = unknown authorId,
-- caller counts it into warnings.
UPDATE authors SET followed = 1 WHERE id = ?;

-- name: ImportAddAssetAuthor :exec
-- Provenance-aware link upsert (PK asset_id+author_id in 0001; ADR-0032):
-- the caller stamps the transferred (created_at, origin) -- origin
-- normalized by store.NormalizeOrigin, created_at falls back to the
-- import moment when the backup lacks it. Conflict branch = the
-- DOMAIN_RULES 10 row-level adjudication: heal an untraceable row
-- (origin 'legacy' -> transferred value; created_at NULL -> transferred
-- value) but NEVER overwrite a known one (first-write wins, re-import
-- stays idempotent).
INSERT INTO asset_authors (asset_id, author_id, created_at, origin)
VALUES (?, ?, ?, ?)
ON CONFLICT (asset_id, author_id) DO UPDATE SET
    origin = CASE WHEN asset_authors.origin = 'legacy' THEN excluded.origin ELSE asset_authors.origin END,
    created_at = COALESCE(asset_authors.created_at, excluded.created_at);

-- name: ImportAddLike :exec
-- Idempotent like import: re-importing the same batch must not fail on
-- the (asset_id, day) PK (S10 idempotency); business toggle path keeps
-- its strict INSERT in likes.sql.
INSERT INTO likes (asset_id, day, created_at) VALUES (?, ?, ?)
ON CONFLICT (asset_id, day) DO NOTHING;

-- name: ImportInsertTimelineTag :exec
-- Idempotent timeline-tag import: content-level dedupe on
-- (asset_id, time_millis, name) -- business path re-inserts with fresh
-- uuid by design (replace semantics), import must not duplicate rows.
INSERT INTO timeline_tags (id, asset_id, time_millis, name, created_at)
SELECT ?, ?, ?, ?, ?
WHERE NOT EXISTS (
    SELECT 1 FROM timeline_tags t
    WHERE t.asset_id = ? AND t.time_millis = ? AND t.name = ?
);

-- name: ImportAddAssetTag :exec
-- Provenance-aware tag-ref upsert (S10 "associations upsert by unique
-- key"; ADR-0032). The caller stamps the transferred (created_at, origin)
-- -- origin normalized by store.NormalizeOrigin, created_at falls back to
-- the import moment when the backup lacks it. Conflict branch = the
-- DOMAIN_RULES 10 row-level adjudication: heal an untraceable row
-- (origin 'legacy' -> transferred value; created_at at the 0004 epoch
-- sentinel -- the literal MUST stay byte-identical to the 0004 column
-- default, Go-side single source store.TimestampEpoch documents the sync
-- duty) but NEVER overwrite a known one (first association's created_at
-- wins, re-import stays idempotent).
INSERT INTO asset_tags (asset_id, tag_id, created_at, origin) VALUES (?, ?, ?, ?)
ON CONFLICT (asset_id, tag_id) DO UPDATE SET
    origin = CASE WHEN asset_tags.origin = 'legacy' THEN excluded.origin ELSE asset_tags.origin END,
    created_at = CASE WHEN asset_tags.created_at = '1970-01-01T00:00:00.000Z'
                      THEN excluded.created_at ELSE asset_tags.created_at END;

-- name: ListAssetNameIndex :many
-- Full name->path projection for legacy-import file matching (audit R1,
-- 2026-09-20): one scan builds an in-memory fileName index instead of one
-- query per backup file (the old N+1). ORDER BY created_at keeps
-- ListAssetsByFileName's earliest-first tie-break (folder disambiguation
-- falls back to rows[0] -- behavior must stay identical).
SELECT asset_id, rel_path, file_name
FROM assets
ORDER BY created_at;
