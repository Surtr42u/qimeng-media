-- history queries: GET /api/v1/history watch history (M3, DOMAIN_RULES 8
-- browse surface). One row per asset = its latest kind='open' ViewEvent
-- timestamp, ordered by that timestamp DESC (newest first).
-- ASCII-only comments here; see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug) and migrations/0001_init.up.sql for
-- Chinese explanations.
--
-- Design notes (single-level pattern, see browse.sql header rules):
--   - view_events has no FK and may reference deleted assets (adr/0005);
--     the FROM assets a anchor drops them (INNER JOIN semantics).
--   - COS exclusion predicate is the same as ListAssetsFilteredDesc in
--     browse.sql (DOMAIN_RULES 6); include_cos = 1 re-adds them.
--   - Keyset cursor = (last_viewed_at, asset_id) of the last row of the
--     previous page; last_viewed_at is the RFC3339-ms TEXT kept in the
--     store-wide format, so lexicographic order == chronological order
--     (migrations/0001_init.up.sql header convention).
--   - The MAX(started_at) scalar subquery repeats in the cursor branch
--     because of parser rule 3 (no derived-table/CTE alias references).

-- name: ListHistory :many
SELECT
    a.asset_id, a.file_name, a.media_type, a.size_bytes, a.mtime,
    a.duration_ms, a.last_position_seconds, a.source, a.created_at,
    EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
    (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count,
    (SELECT MAX(v.started_at) FROM view_events v
        WHERE v.asset_id = a.asset_id AND v.kind = 'open') AS last_viewed_at
FROM assets a
WHERE EXISTS (SELECT 1 FROM view_events ve
              WHERE ve.asset_id = a.asset_id AND ve.kind = 'open')
    AND (sqlc.arg(include_cos) = 1 OR NOT EXISTS (
        SELECT 1 FROM asset_authors aa
        JOIN authors au ON au.id = aa.author_id
        WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
    AND (sqlc.narg(cursor_key) IS NULL
        OR (SELECT MAX(v2.started_at) FROM view_events v2
            WHERE v2.asset_id = a.asset_id AND v2.kind = 'open') < sqlc.narg(cursor_key)
        OR ((SELECT MAX(v2.started_at) FROM view_events v2
            WHERE v2.asset_id = a.asset_id AND v2.kind = 'open') = sqlc.narg(cursor_key)
            AND a.asset_id < sqlc.narg(cursor_id)))
ORDER BY last_viewed_at DESC, a.asset_id DESC
LIMIT sqlc.arg(row_limit);
