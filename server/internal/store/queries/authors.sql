-- authors.sql: business-side author queries (M3 author system,
-- DOMAIN_RULES 6). The one-shot legacy import channel owns the Import*
-- prefixed queries in legacy_import.sql; every query here is deliberately
-- named outside that namespace (sqlc would collide otherwise).
-- ASCII-only comments here; see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug) and migrations/0001_init.up.sql for
-- Chinese explanations of the schema.
--
-- NOTE: the asset_characters write queries live in this file too (not
-- assets.sql) because this task's change scope names authors.sql as the
-- only new query file; they are the scanner-side output of the source
-- matching engine and read back via browse.sql ListAssetCharacterNames.

-- name: ListAuthors :many
-- GET /authors: every author (regular + COS unified, DOMAIN_RULES 6)
-- with its linked-asset count, cumulative view count and follow flag.
-- Sort = display_name ascending, same convention as GET /tags
-- (ListTags ORDER BY t.name, DOMAIN_RULES 7 name ordering).
-- view_count = author's works' total kind='open' events (one scalar
-- subquery per linked row; aa.asset_id NULL rows contribute 0, so the
-- SUM never yields NULL; COALESCE keeps the aggregate defensive).
SELECT au.id, au.display_name, au.type, au.followed,
       COUNT(aa.asset_id) AS file_count,
       COALESCE(SUM(CASE WHEN aa.asset_id IS NULL THEN 0 ELSE
           (SELECT COUNT(*) FROM view_events ve
            WHERE ve.asset_id = aa.asset_id AND ve.kind = 'open') END), 0) AS view_count
FROM authors au
LEFT JOIN asset_authors aa ON aa.author_id = au.id
GROUP BY au.id
ORDER BY au.display_name;

-- name: UpsertAuthor :exec
-- TXT import / COS scan author upsert keyed by the authoring-generated
-- id (GenerateAuthorID / GenerateCosAuthorID). Re-import never
-- duplicates authors; display_name tracks the (re)imported value. id is
-- the identity, so the ON CONFLICT branch must not touch followed
-- (re-importing a TXT must not reset the follow flag) nor created_at
-- (cold-start freshness, same rule as UpsertAsset).
INSERT INTO authors (id, display_name, type, followed, created_at)
VALUES (?, ?, ?, 0, ?)
ON CONFLICT (id) DO UPDATE SET
    display_name = excluded.display_name;

-- name: SetAuthorFollow :execrows
-- Follow is an author-level boolean (DOMAIN_RULES 5/6: no counter,
-- unsetting just clears it). 0 rows affected = unknown authorId; the
-- caller maps that to 404.
UPDATE authors SET followed = ? WHERE id = ?;

-- name: AddAssetAuthor :exec
-- Idempotent many-to-many link (PK asset_id+author_id in 0001). Used by
-- the TXT unified rebuild and by COS scanning (author -> files mapping
-- recorded at scan time, GUIDE_AUTHOR).
INSERT INTO asset_authors (asset_id, author_id)
VALUES (?, ?)
ON CONFLICT (asset_id, author_id) DO NOTHING;

-- name: DeleteAssetAuthorsByAuthorIds :exec
-- Unified rebuild step 1: drop every existing link of all authors that
-- appear in any imported TXT source, then the caller re-inserts the
-- fully merged set (cross-TXT union semantics: no single TXT may
-- overwrite another TXT's links; old-project
-- rebuildAssociationsFromBlocks behavior).
DELETE FROM asset_authors
WHERE author_id IN (sqlc.slice('author_ids'));

-- name: ListNormalAssetsForAuthorMatch :many
-- The TXT work-name matching domain: all assets of normal libraries.
-- COS assets are excluded by the DOMAIN_RULES 6 isolation rule (regular
-- authors only ever match non-COS files).
SELECT a.asset_id, a.file_name
FROM assets a
JOIN libraries l ON l.id = a.library_id
WHERE l.kind = 'normal';

-- name: DeleteAssetCharacters :exec
-- Character rows are scanner-recomputed output: delete-then-insert on
-- every (re)ingest = upsert-with-overwrite semantics. Rename/ moves that
-- change mtime re-ingest through UpsertAsset (whose DO UPDATE already
-- overwrites the source column via excluded.source) followed by this
-- delete+insert, so both enrichment columns are recomputed from the new
-- file name.
DELETE FROM asset_characters WHERE asset_id = ?;

-- name: AddAssetCharacter :exec
-- One row per matched character canonical name (multi-character assets
-- get multiple rows; PK asset_id+character_name dedupes).
INSERT INTO asset_characters (asset_id, character_name)
VALUES (?, ?)
ON CONFLICT (asset_id, character_name) DO NOTHING;

-- name: UpdateAssetSource :exec
-- Single-asset source refresh (EnrichAsset after API move/rename:
-- the file name changes but size+mtime do not, so no re-ingest would
-- happen; the write path must recompute explicitly).
UPDATE assets SET source = ?, updated_at = ? WHERE asset_id = ?;

-- name: UpdateAssetCosWork :exec
-- Single-asset COS work refresh (migration 0008). recomputeCosAuthor calls
-- it on every path that changes rel_path without re-ingesting (API
-- move/rename via EnrichAsset, scan move-merge): the work is derived from
-- rel_path, so it must be recomputed together with the author links.
-- NULL work = file sits directly under the author directory (old-app
-- "other" bucket, DOMAIN_RULES 6) -- the refresh is overwrite-in-full,
-- including the NULL case, so a file moved out of a work directory
-- unlinks from it.
UPDATE assets SET cos_work = ?, updated_at = ? WHERE asset_id = ?;

-- name: DeleteAssetAuthorsByAssetID :exec
-- Single-asset link removal (EnrichAsset COS branch recompute:
-- delete-then-insert, same overwrite semantics as asset_characters).
DELETE FROM asset_authors WHERE asset_id = ?;

-- name: DeleteOrphanCosAuthors :execrows
-- Post-scan garbage collection: COS authors arise from scanned directory
-- structure; once every file of an author directory is gone (rename/delete
-- at the filesystem level), the author row has zero links and must be
-- removed (old-project deleteOrphanCosAuthors semantics; the authors.type
-- CHECK on 'cos' is more reliable than a LIKE 'cos\\_%' prefix).
DELETE FROM authors
WHERE type = 'cos'
  AND NOT EXISTS (SELECT 1 FROM asset_authors aa WHERE aa.author_id = authors.id);

-- name: ListAssetsForEnrichmentByLibrary :many
-- Full-library asset rows for explicit recomputation (custom_sources
-- change: already-ingested assets are skipped by a rescan because
-- size+mtime match, so enrichment must be recomputed on the write path).
SELECT asset_id, file_name, rel_path FROM assets WHERE library_id = ?;

-- name: ListAuthorNamesForAssets :many
-- Batch author-name lookup for list endpoints (AssetSummary.authorNames):
-- every author (regular + COS, no type filter) of the given assets, one
-- row per (asset_id, author) pair. asset_ids_json is a JSON array
-- consumed by json_each -- same parameter shape as the browse filter
-- params (see browse.sql header for the sqlc parser constraints that
-- dictate it). Names sort by display_name, same convention as the
-- detail-side ListAssetAuthorRefs.
SELECT aa.asset_id, au.display_name
FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
WHERE aa.asset_id IN (SELECT value FROM json_each(sqlc.narg(asset_ids_json)))
ORDER BY aa.asset_id, au.display_name;
