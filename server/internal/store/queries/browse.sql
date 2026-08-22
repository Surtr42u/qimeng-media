-- browse queries (M1 httpapi browse loop: filtered/sorted/paginated asset
-- list, detail assembly, engagement writes).
-- ASCII-only comments here; see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug) and migrations/0001_init.up.sql for
-- Chinese explanations.
--
-- ============ Why this file looks the way it does ============
-- The sqlc v1.31.1 SQLite parser was probed in a lab before writing
-- these queries; the shapes below are exactly the subset it accepts:
--   1. Parameter macros must use the BARE form sqlc.arg(x)/sqlc.narg(x).
--      The "?sqlc.arg(x)" prefix form from the docs fails to parse.
--   2. A parameter may not be the subject of CASE ... WHEN; it must sit
--      inside a WHEN comparison (CASE WHEN sqlc.arg(sort) = 'name' ...).
--      Exception: CASE sqlc.narg(x) WHEN 'lit' (parameter as subject)
--      parses when every branch is a literal test.
--   3. WHERE cannot reference derived-table/CTE aliases ("table alias
--      does not exist") -> the queries stay SINGLE-LEVEL and repeat
--      scalar subqueries / CASE expressions inline instead of joining
--      a projection subquery.
--   4. sqlc.arg() inside ORDER BY is NOT macro-expanded (left verbatim
--      in the generated SQL, which would crash SQLite at runtime) ->
--      sort DIRECTION is baked into two query variants (Asc/Desc)
--      instead of being a runtime parameter. ORDER BY may reference
--      SELECT-projection aliases, which the sort_key column uses.
--
-- ============ Sorting design ============
-- 7 sort keys (openapi sort enum) collapse into ONE TEXT "sort_key"
-- expression per query:
--   - TEXT keys (fileDate=mtime / addedDate=created_at / name) pass
--     through: the store-wide RFC3339-ms format keeps lexicographic
--     order == chronological order by design.
--   - INTEGER keys (sizeBytes / viewCount / playCount) go through
--     printf('%020d', n): counts and sizes are >= 0 and fit in 20
--     digits, so numeric order == lexicographic order.
-- The unified TEXT key makes the keyset cursor generic: the cursor is
-- just (sort_key, asset_id) of the last row of the previous page.
-- 'default' falls back to addedDate for M1 (the real default sort is
-- the recommendation algorithm, which lands with M2/M3).
--
-- ============ Cursor predicate ============
-- Strict tuple comparison (sort_key, asset_id) < (or >) the cursor,
-- expanded row-value form, mirroring ListAssetsAfterCursor in
-- assets.sql. The sort_key CASE expression is repeated inline because
-- of parser rule 3 above -- THE INLINE COPIES MUST STAY IDENTICAL TO
-- THE PROJECTION COPY.
--
-- ============ Performance note ============
-- The view/play count scalar subqueries repeat several times per query
-- (projection + cursor + range buckets). Each hit is an indexed lookup
-- on idx_view_events_asset, and M1 libraries are tens of thousands of
-- rows; re-visit only if profiling says so.

-- name: ListAssetsFilteredDesc :many
SELECT
    a.asset_id, a.library_id, a.rel_path, a.file_name, a.media_type,
    a.size_bytes, a.mtime, a.duration_ms, a.width, a.height, a.source,
    a.created_at, a.updated_at,
    EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
    (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count,
    (SELECT COUNT(*) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'open') AS view_count,
    (SELECT COUNT(*) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'play') AS play_count,
    CASE
        WHEN sqlc.arg(sort) = 'fileDate'  THEN a.mtime
        WHEN sqlc.arg(sort) = 'name'      THEN a.file_name
        WHEN sqlc.arg(sort) = 'sizeBytes' THEN printf('%020d', a.size_bytes)
        WHEN sqlc.arg(sort) = 'viewCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v2 WHERE v2.asset_id = a.asset_id AND v2.kind = 'open'))
        WHEN sqlc.arg(sort) = 'playCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v3 WHERE v3.asset_id = a.asset_id AND v3.kind = 'play'))
        ELSE a.created_at
    END AS sort_key
FROM assets a
WHERE
    (sqlc.narg(library_id) IS NULL OR a.library_id = sqlc.narg(library_id))
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    -- "no source group" is stored as NULL; the caller translates the
    -- user-facing OTHER bucket into source_is_other = 1 (keeps SQL
    -- literals ASCII for parser safety, see file header).
    AND (sqlc.narg(source) IS NULL
         OR (sqlc.arg(source_is_other) = 1 AND a.source IS NULL)
         OR a.source = sqlc.narg(source))
    -- Default COS exclusion (DOMAIN_RULES 6: COS files never appear in
    -- the regular browse stream); include_cos = 1 re-adds them.
    AND (sqlc.arg(include_cos) = 1 OR NOT EXISTS (
        SELECT 1 FROM asset_authors aa
        JOIN authors au ON au.id = aa.author_id
        WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
    -- character filter: 'a+b' is split by the caller into a JSON array
    -- of canonical names; ALL names must be attached (multi-character
    -- group semantics).
    AND (sqlc.narg(characters_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(characters_json)) c
        WHERE NOT EXISTS (
            SELECT 1 FROM asset_characters ac
            WHERE ac.asset_id = a.asset_id AND ac.character_name = c.value)))
    AND (sqlc.narg(author_id) IS NULL OR EXISTS (
        SELECT 1 FROM asset_authors aa2
        WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
    -- tag filter: fuzzy = ANY selected tag matches; exact = ALL
    -- selected tags must be attached (DOMAIN_RULES 3).
    AND (sqlc.narg(tag_ids_json) IS NULL OR (
        CASE WHEN sqlc.arg(tag_mode) = 'exact' THEN
            NOT EXISTS (
                SELECT 1 FROM json_each(sqlc.narg(tag_ids_json)) jt
                WHERE NOT EXISTS (
                    SELECT 1 FROM asset_tags at2
                    WHERE at2.asset_id = a.asset_id AND at2.tag_id = jt.value))
        ELSE
            EXISTS (
                SELECT 1 FROM json_each(sqlc.narg(tag_ids_json)) jt
                WHERE EXISTS (
                    SELECT 1 FROM asset_tags at2
                    WHERE at2.asset_id = a.asset_id AND at2.tag_id = jt.value))
        END))
    AND (sqlc.narg(favorite) IS NULL OR (
        CASE WHEN sqlc.narg(favorite) = 1 THEN
            EXISTS(SELECT 1 FROM favorites fv2 WHERE fv2.asset_id = a.asset_id)
        ELSE
            NOT EXISTS(SELECT 1 FROM favorites fv2 WHERE fv2.asset_id = a.asset_id)
        END))
    -- file-date window: TEXT comparison against the RFC3339-ms format;
    -- boundaries precomputed by the caller (dateFrom -> T00:00:00.000Z,
    -- dateTo -> T23:59:59.999Z).
    AND (sqlc.narg(mtime_from) IS NULL OR a.mtime >= sqlc.narg(mtime_from))
    AND (sqlc.narg(mtime_to) IS NULL OR a.mtime <= sqlc.narg(mtime_to))
    AND (sqlc.narg(year_from) IS NULL OR CAST(substr(a.mtime, 1, 4) AS INTEGER) >= sqlc.narg(year_from))
    AND (sqlc.narg(year_to) IS NULL OR CAST(substr(a.mtime, 1, 4) AS INTEGER) <= sqlc.narg(year_to))
    -- viewRange / playRange buckets (DOMAIN_RULES 3 literal boundaries:
    -- low = 1-5, mid = 5-20 -- the value 5 falls in both, by the book).
    AND (sqlc.narg(view_range) IS NULL OR (
        CASE sqlc.narg(view_range)
            WHEN 'none' THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') = 0
            WHEN 'low'  THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') BETWEEN 1 AND 5
            WHEN 'mid'  THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') BETWEEN 5 AND 20
            WHEN 'high' THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') > 20
            ELSE 1
        END))
    AND (sqlc.narg(play_range) IS NULL OR (
        CASE sqlc.narg(play_range)
            WHEN 'none' THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') = 0
            WHEN 'low'  THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') BETWEEN 1 AND 5
            WHEN 'mid'  THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') BETWEEN 5 AND 20
            WHEN 'high' THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') > 20
            ELSE 1
        END))
    -- sizeRange buckets; MB = 1024*1024 (file-size convention).
    AND (sqlc.narg(size_range) IS NULL OR (
        CASE sqlc.narg(size_range)
            WHEN 'lt1m'    THEN a.size_bytes < 1048576
            WHEN 'm1to10'  THEN a.size_bytes >= 1048576 AND a.size_bytes < 10485760
            WHEN 'm10to50' THEN a.size_bytes >= 10485760 AND a.size_bytes < 52428800
            WHEN 'gt50m'   THEN a.size_bytes >= 52428800
            ELSE 1
        END))
    -- keyset cursor (DESC variant): strict (sort_key, asset_id) tuple
    -- comparison. The sort_key CASE repeats inline (parser rule 3).
    AND (sqlc.narg(cursor_key) IS NULL
        OR (CASE
                WHEN sqlc.arg(sort) = 'fileDate'  THEN a.mtime
                WHEN sqlc.arg(sort) = 'name'      THEN a.file_name
                WHEN sqlc.arg(sort) = 'sizeBytes' THEN printf('%020d', a.size_bytes)
                WHEN sqlc.arg(sort) = 'viewCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v6 WHERE v6.asset_id = a.asset_id AND v6.kind = 'open'))
                WHEN sqlc.arg(sort) = 'playCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v7 WHERE v7.asset_id = a.asset_id AND v7.kind = 'play'))
                ELSE a.created_at
            END) < sqlc.narg(cursor_key)
        OR ((CASE
                WHEN sqlc.arg(sort) = 'fileDate'  THEN a.mtime
                WHEN sqlc.arg(sort) = 'name'      THEN a.file_name
                WHEN sqlc.arg(sort) = 'sizeBytes' THEN printf('%020d', a.size_bytes)
                WHEN sqlc.arg(sort) = 'viewCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v6 WHERE v6.asset_id = a.asset_id AND v6.kind = 'open'))
                WHEN sqlc.arg(sort) = 'playCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v7 WHERE v7.asset_id = a.asset_id AND v7.kind = 'play'))
                ELSE a.created_at
            END) = sqlc.narg(cursor_key)
            AND a.asset_id < sqlc.narg(cursor_id)))
ORDER BY sort_key DESC, a.asset_id DESC
LIMIT sqlc.arg(row_limit);

-- name: ListAssetsFilteredAsc :many
SELECT
    a.asset_id, a.library_id, a.rel_path, a.file_name, a.media_type,
    a.size_bytes, a.mtime, a.duration_ms, a.width, a.height, a.source,
    a.created_at, a.updated_at,
    EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
    (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count,
    (SELECT COUNT(*) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'open') AS view_count,
    (SELECT COUNT(*) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'play') AS play_count,
    CASE
        WHEN sqlc.arg(sort) = 'fileDate'  THEN a.mtime
        WHEN sqlc.arg(sort) = 'name'      THEN a.file_name
        WHEN sqlc.arg(sort) = 'sizeBytes' THEN printf('%020d', a.size_bytes)
        WHEN sqlc.arg(sort) = 'viewCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v2 WHERE v2.asset_id = a.asset_id AND v2.kind = 'open'))
        WHEN sqlc.arg(sort) = 'playCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v3 WHERE v3.asset_id = a.asset_id AND v3.kind = 'play'))
        ELSE a.created_at
    END AS sort_key
FROM assets a
WHERE
    (sqlc.narg(library_id) IS NULL OR a.library_id = sqlc.narg(library_id))
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    AND (sqlc.narg(source) IS NULL
         OR (sqlc.arg(source_is_other) = 1 AND a.source IS NULL)
         OR a.source = sqlc.narg(source))
    AND (sqlc.arg(include_cos) = 1 OR NOT EXISTS (
        SELECT 1 FROM asset_authors aa
        JOIN authors au ON au.id = aa.author_id
        WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
    AND (sqlc.narg(characters_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(characters_json)) c
        WHERE NOT EXISTS (
            SELECT 1 FROM asset_characters ac
            WHERE ac.asset_id = a.asset_id AND ac.character_name = c.value)))
    AND (sqlc.narg(author_id) IS NULL OR EXISTS (
        SELECT 1 FROM asset_authors aa2
        WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
    AND (sqlc.narg(tag_ids_json) IS NULL OR (
        CASE WHEN sqlc.arg(tag_mode) = 'exact' THEN
            NOT EXISTS (
                SELECT 1 FROM json_each(sqlc.narg(tag_ids_json)) jt
                WHERE NOT EXISTS (
                    SELECT 1 FROM asset_tags at2
                    WHERE at2.asset_id = a.asset_id AND at2.tag_id = jt.value))
        ELSE
            EXISTS (
                SELECT 1 FROM json_each(sqlc.narg(tag_ids_json)) jt
                WHERE EXISTS (
                    SELECT 1 FROM asset_tags at2
                    WHERE at2.asset_id = a.asset_id AND at2.tag_id = jt.value))
        END))
    AND (sqlc.narg(favorite) IS NULL OR (
        CASE WHEN sqlc.narg(favorite) = 1 THEN
            EXISTS(SELECT 1 FROM favorites fv2 WHERE fv2.asset_id = a.asset_id)
        ELSE
            NOT EXISTS(SELECT 1 FROM favorites fv2 WHERE fv2.asset_id = a.asset_id)
        END))
    AND (sqlc.narg(mtime_from) IS NULL OR a.mtime >= sqlc.narg(mtime_from))
    AND (sqlc.narg(mtime_to) IS NULL OR a.mtime <= sqlc.narg(mtime_to))
    AND (sqlc.narg(year_from) IS NULL OR CAST(substr(a.mtime, 1, 4) AS INTEGER) >= sqlc.narg(year_from))
    AND (sqlc.narg(year_to) IS NULL OR CAST(substr(a.mtime, 1, 4) AS INTEGER) <= sqlc.narg(year_to))
    AND (sqlc.narg(view_range) IS NULL OR (
        CASE sqlc.narg(view_range)
            WHEN 'none' THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') = 0
            WHEN 'low'  THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') BETWEEN 1 AND 5
            WHEN 'mid'  THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') BETWEEN 5 AND 20
            WHEN 'high' THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') > 20
            ELSE 1
        END))
    AND (sqlc.narg(play_range) IS NULL OR (
        CASE sqlc.narg(play_range)
            WHEN 'none' THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') = 0
            WHEN 'low'  THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') BETWEEN 1 AND 5
            WHEN 'mid'  THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') BETWEEN 5 AND 20
            WHEN 'high' THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') > 20
            ELSE 1
        END))
    AND (sqlc.narg(size_range) IS NULL OR (
        CASE sqlc.narg(size_range)
            WHEN 'lt1m'    THEN a.size_bytes < 1048576
            WHEN 'm1to10'  THEN a.size_bytes >= 1048576 AND a.size_bytes < 10485760
            WHEN 'm10to50' THEN a.size_bytes >= 10485760 AND a.size_bytes < 52428800
            WHEN 'gt50m'   THEN a.size_bytes >= 52428800
            ELSE 1
        END))
    -- keyset cursor (ASC variant).
    AND (sqlc.narg(cursor_key) IS NULL
        OR (CASE
                WHEN sqlc.arg(sort) = 'fileDate'  THEN a.mtime
                WHEN sqlc.arg(sort) = 'name'      THEN a.file_name
                WHEN sqlc.arg(sort) = 'sizeBytes' THEN printf('%020d', a.size_bytes)
                WHEN sqlc.arg(sort) = 'viewCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v6 WHERE v6.asset_id = a.asset_id AND v6.kind = 'open'))
                WHEN sqlc.arg(sort) = 'playCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v7 WHERE v7.asset_id = a.asset_id AND v7.kind = 'play'))
                ELSE a.created_at
            END) > sqlc.narg(cursor_key)
        OR ((CASE
                WHEN sqlc.arg(sort) = 'fileDate'  THEN a.mtime
                WHEN sqlc.arg(sort) = 'name'      THEN a.file_name
                WHEN sqlc.arg(sort) = 'sizeBytes' THEN printf('%020d', a.size_bytes)
                WHEN sqlc.arg(sort) = 'viewCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v6 WHERE v6.asset_id = a.asset_id AND v6.kind = 'open'))
                WHEN sqlc.arg(sort) = 'playCount' THEN printf('%020d', (SELECT COUNT(*) FROM view_events v7 WHERE v7.asset_id = a.asset_id AND v7.kind = 'play'))
                ELSE a.created_at
            END) = sqlc.narg(cursor_key)
            AND a.asset_id > sqlc.narg(cursor_id)))
ORDER BY sort_key ASC, a.asset_id ASC
LIMIT sqlc.arg(row_limit);

-- name: CountAssetsFiltered :one
-- Same filter matrix minus cursor/order/limit (totalMatched field of
-- the AssetPage response). Keep the WHERE clauses in sync with the two
-- list queries above.
SELECT COUNT(*) FROM assets a
WHERE
    (sqlc.narg(library_id) IS NULL OR a.library_id = sqlc.narg(library_id))
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    AND (sqlc.narg(source) IS NULL
         OR (sqlc.arg(source_is_other) = 1 AND a.source IS NULL)
         OR a.source = sqlc.narg(source))
    AND (sqlc.arg(include_cos) = 1 OR NOT EXISTS (
        SELECT 1 FROM asset_authors aa
        JOIN authors au ON au.id = aa.author_id
        WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
    AND (sqlc.narg(characters_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(characters_json)) c
        WHERE NOT EXISTS (
            SELECT 1 FROM asset_characters ac
            WHERE ac.asset_id = a.asset_id AND ac.character_name = c.value)))
    AND (sqlc.narg(author_id) IS NULL OR EXISTS (
        SELECT 1 FROM asset_authors aa2
        WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
    AND (sqlc.narg(tag_ids_json) IS NULL OR (
        CASE WHEN sqlc.arg(tag_mode) = 'exact' THEN
            NOT EXISTS (
                SELECT 1 FROM json_each(sqlc.narg(tag_ids_json)) jt
                WHERE NOT EXISTS (
                    SELECT 1 FROM asset_tags at2
                    WHERE at2.asset_id = a.asset_id AND at2.tag_id = jt.value))
        ELSE
            EXISTS (
                SELECT 1 FROM json_each(sqlc.narg(tag_ids_json)) jt
                WHERE EXISTS (
                    SELECT 1 FROM asset_tags at2
                    WHERE at2.asset_id = a.asset_id AND at2.tag_id = jt.value))
        END))
    AND (sqlc.narg(favorite) IS NULL OR (
        CASE WHEN sqlc.narg(favorite) = 1 THEN
            EXISTS(SELECT 1 FROM favorites fv2 WHERE fv2.asset_id = a.asset_id)
        ELSE
            NOT EXISTS(SELECT 1 FROM favorites fv2 WHERE fv2.asset_id = a.asset_id)
        END))
    AND (sqlc.narg(mtime_from) IS NULL OR a.mtime >= sqlc.narg(mtime_from))
    AND (sqlc.narg(mtime_to) IS NULL OR a.mtime <= sqlc.narg(mtime_to))
    AND (sqlc.narg(year_from) IS NULL OR CAST(substr(a.mtime, 1, 4) AS INTEGER) >= sqlc.narg(year_from))
    AND (sqlc.narg(year_to) IS NULL OR CAST(substr(a.mtime, 1, 4) AS INTEGER) <= sqlc.narg(year_to))
    AND (sqlc.narg(view_range) IS NULL OR (
        CASE sqlc.narg(view_range)
            WHEN 'none' THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') = 0
            WHEN 'low'  THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') BETWEEN 1 AND 5
            WHEN 'mid'  THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') BETWEEN 5 AND 20
            WHEN 'high' THEN (SELECT COUNT(*) FROM view_events v4 WHERE v4.asset_id = a.asset_id AND v4.kind = 'open') > 20
            ELSE 1
        END))
    AND (sqlc.narg(play_range) IS NULL OR (
        CASE sqlc.narg(play_range)
            WHEN 'none' THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') = 0
            WHEN 'low'  THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') BETWEEN 1 AND 5
            WHEN 'mid'  THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') BETWEEN 5 AND 20
            WHEN 'high' THEN (SELECT COUNT(*) FROM view_events v5 WHERE v5.asset_id = a.asset_id AND v5.kind = 'play') > 20
            ELSE 1
        END))
    AND (sqlc.narg(size_range) IS NULL OR (
        CASE sqlc.narg(size_range)
            WHEN 'lt1m'    THEN a.size_bytes < 1048576
            WHEN 'm1to10'  THEN a.size_bytes >= 1048576 AND a.size_bytes < 10485760
            WHEN 'm10to50' THEN a.size_bytes >= 10485760 AND a.size_bytes < 52428800
            WHEN 'gt50m'   THEN a.size_bytes >= 52428800
            ELSE 1
        END));

-- GetAssetWithLibrary: detail/media-serving join -- serving /media/**
-- needs the library root to rebuild the absolute path. Explicit column
-- list (not a.*) to avoid ambiguous created_at between the two tables.

-- name: GetAssetWithLibrary :one
SELECT
    a.asset_id, a.library_id, a.rel_path, a.file_name, a.media_type,
    a.size_bytes, a.mtime, a.duration_ms, a.width, a.height, a.source,
    a.created_at, a.updated_at, l.root_path
FROM assets a
JOIN libraries l ON l.id = a.library_id
WHERE a.asset_id = ?;

-- Detail assembly queries (tags / authors / characters / stats).

-- name: ListAssetCharacterNames :many
SELECT character_name FROM asset_characters
WHERE asset_id = ?
ORDER BY character_name;

-- name: ListAssetTagRefs :many
SELECT t.id, t.name
FROM asset_tags at JOIN tags t ON t.id = at.tag_id
WHERE at.asset_id = ?
ORDER BY t.name;

-- name: ListAssetAuthorRefs :many
SELECT au.id, au.display_name, au.type
FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
WHERE aa.asset_id = ?
ORDER BY au.display_name;

-- name: LastViewedAt :one
SELECT MAX(started_at) FROM view_events
WHERE asset_id = ? AND kind = 'open';

-- name: SumBrowseSeconds :one
SELECT COALESCE(SUM(seconds), 0) FROM view_events
WHERE asset_id = ? AND kind = 'dwell';

-- RemoveLikeOnDay: the un-like half of the daily like toggle
-- (openapi PUT .../like is a toggle; DOMAIN_RULES 5 daily reset is
-- implied by the day-scoped DELETE).

-- name: RemoveLikeOnDay :execrows
DELETE FROM likes WHERE asset_id = ? AND day = ?;

-- CountLibraryMedia: per-library media_type counters for the Library
-- response (fileCount = sum of the three rows; absent rows = 0).

-- name: CountLibraryMedia :many
SELECT media_type, COUNT(*) AS cnt FROM assets
WHERE library_id = ?
GROUP BY media_type;
