-- recommend queries: one row per asset with the aggregates the
-- recommendation algorithm (DOMAIN_RULES 1) consumes.
-- ASCII-only comments here; see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug). Chinese explanations live in
-- migrations/0001_init.up.sql and docs/DOMAIN_RULES.md.
--
-- Why one row per asset instead of joins: SQLite aggregation fan-out
-- (likes x view_events x daily_shown) would multiply rows; scalar
-- subqueries keep the row count == asset count (single-level pattern,
-- see browse.sql header rule 3).
--
-- last_viewed_at is MAX(started_at) of kind='open' -> NULL when the
-- asset was never opened; the algorithm maps NULL to the recency
-- default 0.3 (DOMAIN_RULES 1.1).

-- name: ListAssetsRecommendInput :many
SELECT
    a.asset_id, a.library_id, a.rel_path, a.file_name, a.media_type,
    a.size_bytes, a.mtime, a.duration_ms, a.source, a.created_at,
    EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
    (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count,
    (SELECT COUNT(*) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'open') AS view_count,
    (SELECT COUNT(*) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'play') AS play_count,
    (SELECT COALESCE(SUM(v.seconds), 0) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'dwell') AS browse_seconds,
    (SELECT MAX(v.started_at) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'open') AS last_viewed_at,
    -- Column refs (ds.count) with a zero-row FROM return an EMPTY result
    -- set -> NULL in expression context; COALESCE outside the zero-row
    -- select is not executed. Nest the row pick inside COALESCE:
    -- zero rows -> inner select yields NULL -> 0. (SUM works without the
    -- nesting because aggregates always produce one row.)
    (SELECT COALESCE((SELECT ds.count FROM daily_shown ds
        WHERE ds.asset_id = a.asset_id AND ds.day = sqlc.arg(day)), 0)) AS shown_today
FROM assets a
WHERE
    -- library kill-switch: disabled libraries vanish from browse/search/
    -- recommend lists; all records are kept (migration 0007, adr/0012)
    EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    -- COS isolation vs COS-only mode (DOMAIN_RULES 6): cos_only = 0 keeps
    -- the historical regular-stream exclusion (COS never enters regular
    -- streams); cos_only = 1 restricts candidates to COS-linked assets
    -- (home cos tab = legacy "COS recommend mode": same scoring, same
    -- daily-shown penalty). Two explicit branches keep the param strictly
    -- 0/1 two-valued (no NULL three-valued-logic rows) -- same shape as
    -- the browse partition predicate in browse.sql. Handler must always
    -- pass 0 or 1 (never NULL).
    AND ((sqlc.arg(cos_only) = 1 AND EXISTS (
              SELECT 1 FROM asset_authors aacos
              JOIN authors aucos ON aucos.id = aacos.author_id
              WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos'))
         OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
              SELECT 1 FROM asset_authors aa
              JOIN authors au ON au.id = aa.author_id
              WHERE aa.asset_id = a.asset_id AND au.type = 'cos')))
ORDER BY a.asset_id;
