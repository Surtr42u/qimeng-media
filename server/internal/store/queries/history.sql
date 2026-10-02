-- history queries: GET /api/v1/history watch history (M3, DOMAIN_RULES 8
-- browse surface). One row per asset = its latest kind='open' ViewEvent
-- timestamp, ordered by that timestamp DESC (newest first).
-- ASCII-only comments here; see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug) and migrations/0001_init.up.sql for
-- Chinese explanations.
--
-- Design notes, see browse.sql header rules; recommend.sql is the
-- pre-grouped-join precedent this query now follows (2026-10-02 rewrite):
--   - view_events has no FK and may reference deleted assets (adr/0005);
--     the INNER JOIN on assets drops orphaned events. The join IS the
--     anchor: latest only holds event-bearing assets and the ON clause
--     requires a non-NULL open timestamp, so the old WHERE EXISTS anchor
--     is redundant and was dropped.
--   - Per-asset MAX: computed ONCE per asset in the `latest` CTE
--     (GROUP BY asset_id) instead of per-row correlated scalar
--     subqueries. The old shape repeated the same correlated MAX in
--     THREE places (projection + cursor < + cursor =); each copy is a
--     per-asset index probe that the planner can silently re-route
--     through ANY view_events index -- the 0013 (kind, started_at)
--     index turned every probe into a full open-event scan
--     (2026-10-02 incident: 43M index steps/request, single page 8.85s,
--     App 10s read timeout, blank history page). The covering index
--     0015 stopped the bleeding; this rewrite removes the vector:
--     the CTE keeps NO WHERE on view_events (a seekable kind predicate
--     is what lets a kind-leading index hijack the GROUP BY into a
--     temp-B-tree scan -- observed with 0013 still in place), so the
--     aggregation is one sequential pass over an asset_id-ordered
--     index, exactly the recommend.sql shape. The plan is locked by
--     store/db history_plan_test (EXPLAIN QUERY PLAN assertions) and
--     the migration-discipline rule lives in adr/0011 (plan re-review
--     for index migrations).
--   - The keyset cursor predicate sits in the JOIN's ON clause because
--     of two more pinned-down v1.31.1 parser facts (probed 2026-10-02):
--       1. WHERE cannot reference CTE/derived-table aliases ("table
--          alias does not exist", sqlc-dev/sqlc#3639, still open);
--          SELECT/ON references parse fine.
--       2. HAVING is worse: sqlc.arg/narg inside HAVING are left
--          VERBATIM in the generated SQL and silently dropped from the
--          params struct (runtime crash) -- macros in ON expand
--          correctly. So the cursor filter (which must reference the
--          per-asset aggregate) lives in ON. For an INNER JOIN an
--          ON-predicate is logically identical to a WHERE-predicate.
--   - COS partition predicate is the three-way switch copied verbatim in
--     shape from browse.sql (DOMAIN_RULES 6 isolation): include_cos=1 ->
--     no restriction; cos_only=1 -> must link a cos author; both 0 ->
--     must NOT link one (regular partition). /history DIFFERS from
--     /assets only in the handler-side default: a MISSING includeCos
--     maps to 1 (all partition -- user decision 2026-09-05: history
--     defaults to regular UNION cos), an explicit false maps to 0.
--   - media_type / sources_json / cos_works_json / characters_json /
--     author_id filters are the same predicate shapes as browse.sql
--     (protocol 2026-09-09: source/work/character are multi-value JSON
--     arrays -- ANY match within the dimension; character combos are
--     arrays of arrays, each combo fully attached); characters 'a+b'
--     arrives split by the caller.
--   - Keyset cursor = (last_viewed_at, asset_id) of the last row of the
--     previous page; last_viewed_at is the RFC3339-ms TEXT kept in the
--     store-wide format, so lexicographic order == chronological order
--     (migrations/0001_init.up.sql header convention).

-- name: ListHistory :many
WITH latest AS (
    SELECT ve.asset_id,
           MAX(CASE WHEN ve.kind = 'open' THEN ve.started_at END) AS last_viewed_at
    FROM view_events ve
    GROUP BY ve.asset_id
)
SELECT
    a.asset_id, a.file_name, a.media_type, a.size_bytes, a.mtime,
    a.duration_ms, a.last_position_seconds, a.source, a.created_at,
    EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
    (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count,
    lv.last_viewed_at
FROM assets a
JOIN latest lv
    ON lv.asset_id = a.asset_id
   AND lv.last_viewed_at IS NOT NULL
   AND (sqlc.narg(cursor_key) IS NULL
        OR lv.last_viewed_at < sqlc.narg(cursor_key)
        OR (lv.last_viewed_at = sqlc.narg(cursor_key) AND a.asset_id < sqlc.narg(cursor_id)))
WHERE
    -- COS partition three-way switch: byte-identical shape to browse.sql
    -- (include_cos / cos_only two-flag form, DOMAIN_RULES 6). The
    -- handler maps a MISSING includeCos to 1 (default = all partition,
    -- user decision 2026-09-05) and cos_only is always passed 0/1.
    (sqlc.arg(include_cos) = 1
     OR (sqlc.arg(cos_only) = 1 AND EXISTS (
         SELECT 1 FROM asset_authors aacos
         JOIN authors aucos ON aucos.id = aacos.author_id
         WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos'))
     OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
         SELECT 1 FROM asset_authors aa
         JOIN authors au ON au.id = aa.author_id
         WHERE aa.asset_id = a.asset_id AND au.type = 'cos')))
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    -- Multi-value source filter (protocol 2026-09-09, browse.sql shape):
    -- sources_json = JSON array, ANY match within the dimension (OR), AND
    -- with every other dimension; source_is_other = 1 adds the NULL-source
    -- regular bucket ("OTHER", not cos-linked); both slots empty = off.
    AND ((sqlc.narg(sources_json) IS NULL AND sqlc.arg(source_is_other) = 0)
         OR (sqlc.arg(source_is_other) = 1 AND a.source IS NULL
             AND NOT EXISTS (
                 SELECT 1 FROM asset_authors aaoth
                 JOIN authors auoth ON auoth.id = aaoth.author_id
                 WHERE aaoth.asset_id = a.asset_id AND auoth.type = 'cos'))
         OR a.source IN (SELECT value FROM json_each(sqlc.narg(sources_json))))
    -- COS work (migration 0008): second path segment of `author/work/file`.
    -- Multi-value (protocol 2026-09-09): JSON array, ANY match (browse.sql shape).
    AND (sqlc.narg(cos_works_json) IS NULL OR a.cos_work IN (SELECT value FROM json_each(sqlc.narg(cos_works_json))))
    -- character filter (protocol 2026-09-09, browse.sql shape):
    -- characters_json = array of combos ('a+b' split by the caller);
    -- combo must be FULLY attached, ANY combo matching = asset in.
    AND (sqlc.narg(characters_json) IS NULL OR EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(characters_json)) combo
        WHERE NOT EXISTS (
            SELECT 1 FROM json_each(combo.value) c
            WHERE NOT EXISTS (
                SELECT 1 FROM asset_characters ac
                WHERE ac.asset_id = a.asset_id AND ac.character_name = c.value))))
    -- author filter (protocol 2026-09-09): same single-value predicate as
    -- browse.sql (author association on the asset).
    AND (sqlc.narg(author_id) IS NULL OR EXISTS (
        SELECT 1 FROM asset_authors aa2
        WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
ORDER BY last_viewed_at DESC, a.asset_id DESC
LIMIT sqlc.arg(row_limit);
