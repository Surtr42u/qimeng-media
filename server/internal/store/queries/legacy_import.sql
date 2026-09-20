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
INSERT INTO authors (id, display_name, type, followed, created_at)
VALUES (?, ?, ?, 0, ?)
ON CONFLICT(id) DO UPDATE SET display_name = excluded.display_name;

-- name: ImportMarkAuthorFollowed :execrows
-- Mark followed (S6 boolean); 0 rows affected = unknown authorId,
-- caller counts it into warnings.
UPDATE authors SET followed = 1 WHERE id = ?;

-- name: ImportAddAssetAuthor :exec
-- Idempotent many-to-many link (PK asset_id+author_id in 0001).
INSERT INTO asset_authors (asset_id, author_id)
VALUES (?, ?)
ON CONFLICT (asset_id, author_id) DO NOTHING;

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
-- Idempotent tag-ref import (S10 "associations upsert by unique key"):
-- the business AddAssetTag is a strict INSERT (runtime replace semantics),
-- re-importing the same backup batch must not fail on the
-- (asset_id, tag_id) PK. First association's created_at wins on re-import.
INSERT INTO asset_tags (asset_id, tag_id, created_at) VALUES (?, ?, ?)
ON CONFLICT (asset_id, tag_id) DO NOTHING;

-- name: ListAssetNameIndex :many
-- Full name->path projection for legacy-import file matching (audit R1,
-- 2026-09-20): one scan builds an in-memory fileName index instead of one
-- query per backup file (the old N+1). ORDER BY created_at keeps
-- ListAssetsByFileName's earliest-first tie-break (folder disambiguation
-- falls back to rows[0] -- behavior must stay identical).
SELECT asset_id, rel_path, file_name
FROM assets
ORDER BY created_at;
