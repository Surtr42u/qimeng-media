-- search queries (full-text index maintenance; browsing searches go
-- through browse.sql's q_json clause).
-- ASCII-only comments: see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug).
--
-- The FTS table is normally maintained by triggers (migration 0002);
-- these two queries only serve the manual rebuild path (index repair
-- after data mishandling, covered by server/internal/search).

-- name: RebuildAssetsFtsClear :execrows
DELETE FROM assets_fts;

-- name: RebuildAssetsFtsFill :execrows
INSERT INTO assets_fts(rowid, all_text, asset_id)
SELECT a.rowid, v.all_text, a.asset_id
FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id;
