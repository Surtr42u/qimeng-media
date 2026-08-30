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
-- with its linked-asset count and follow flag. Sort = display_name
-- ascending, same convention as GET /tags (ListTags ORDER BY t.name,
-- DOMAIN_RULES 7 name ordering).
SELECT au.id, au.display_name, au.type, au.followed,
       COUNT(aa.asset_id) AS file_count
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
