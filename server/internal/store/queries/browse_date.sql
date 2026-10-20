-- Indexed file-date page for the restricted filter subset selected by
-- httpapi.canUseFileDatePage: library and COS partition only. Any other
-- filter must use ListAssetsFiltered*. Do not widen eligibility without
-- adding the corresponding predicate here and equivalence tests.
--
-- Split NULL/non-NULL cursor branches with UNION ALL so the active cursor
-- branch can SEEK the (mtime, asset_id) index instead of scanning past
-- preceding pages. The inactive branch does no asset work. LIMIT remains
-- inside page: favorite/like enrichment and the final sort touch one page.
-- No sentinel dates, runtime SQL rewriting, new indexes or persistent cache.
-- ASCII comments required by the pinned sqlc SQLite parser.

-- name: ListAssetsFileDateDesc :many
WITH page AS (
    SELECT a.asset_id, a.mtime
    FROM assets a
    WHERE sqlc.narg(cursor_key) IS NULL
    AND EXISTS (SELECT 1 FROM libraries le WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.narg(library_id) IS NULL OR a.library_id = sqlc.narg(library_id))
    AND (sqlc.arg(include_cos) = 1
         OR (sqlc.arg(cos_only) = 1 AND EXISTS (
             SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
             WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
         OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
             SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
             WHERE aa.asset_id = a.asset_id AND au.type = 'cos')))
    UNION ALL
    SELECT a.asset_id, a.mtime
    FROM assets a
    WHERE sqlc.narg(cursor_key) IS NOT NULL
      AND (a.mtime, a.asset_id) < (sqlc.narg(cursor_key), sqlc.narg(cursor_id))
    AND EXISTS (SELECT 1 FROM libraries le WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.narg(library_id) IS NULL OR a.library_id = sqlc.narg(library_id))
    AND (sqlc.arg(include_cos) = 1
         OR (sqlc.arg(cos_only) = 1 AND EXISTS (
             SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
             WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
         OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
             SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
             WHERE aa.asset_id = a.asset_id AND au.type = 'cos')))
    ORDER BY mtime DESC, asset_id DESC
    LIMIT sqlc.arg(row_limit)
)
SELECT a.asset_id, a.file_name, a.media_type, a.size_bytes, a.mtime,
       a.duration_ms, a.source, a.created_at,
       EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
       (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count
FROM page JOIN assets a ON a.asset_id = page.asset_id
ORDER BY page.mtime DESC, a.asset_id DESC;

-- name: ListAssetsFileDateAsc :many
WITH page AS (
    SELECT a.asset_id, a.mtime
    FROM assets a
    WHERE sqlc.narg(cursor_key) IS NULL
    AND EXISTS (SELECT 1 FROM libraries le WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.narg(library_id) IS NULL OR a.library_id = sqlc.narg(library_id))
    AND (sqlc.arg(include_cos) = 1
         OR (sqlc.arg(cos_only) = 1 AND EXISTS (
             SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
             WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
         OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
             SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
             WHERE aa.asset_id = a.asset_id AND au.type = 'cos')))
    UNION ALL
    SELECT a.asset_id, a.mtime
    FROM assets a
    WHERE sqlc.narg(cursor_key) IS NOT NULL
      AND (a.mtime, a.asset_id) > (sqlc.narg(cursor_key), sqlc.narg(cursor_id))
    AND EXISTS (SELECT 1 FROM libraries le WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.narg(library_id) IS NULL OR a.library_id = sqlc.narg(library_id))
    AND (sqlc.arg(include_cos) = 1
         OR (sqlc.arg(cos_only) = 1 AND EXISTS (
             SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
             WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
         OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
             SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
             WHERE aa.asset_id = a.asset_id AND au.type = 'cos')))
    ORDER BY mtime ASC, asset_id ASC
    LIMIT sqlc.arg(row_limit)
)
SELECT a.asset_id, a.file_name, a.media_type, a.size_bytes, a.mtime,
       a.duration_ms, a.source, a.created_at,
       EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
       (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count
FROM page JOIN assets a ON a.asset_id = page.asset_id
ORDER BY page.mtime ASC, a.asset_id ASC;
