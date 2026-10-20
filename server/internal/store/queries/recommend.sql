-- Recommendation input plans, selected by store.LoadRecommendationInputs.
-- Keep the original sequential aggregation for broad candidate sets; only
-- sparse scopes use candidate-first event probes. Both produce identical
-- fields/order and keep ALL eligible candidates for scoring. Scope COUNT is
-- advisory, never a semantic gate. No formula/cache/schema changes.
-- sqlc has no query macro: the three eligibility predicates are kept static
-- and their media/library/COS equivalence is locked by baseline tests.
-- ASCII comments required by the pinned sqlc SQLite parser.

-- name: ListAssetsRecommendInput :many
SELECT
    a.asset_id, a.library_id, a.rel_path, a.file_name, a.media_type,
    a.size_bytes, a.mtime, a.duration_ms, a.source, a.created_at,
    EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
    (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count,
    COALESCE(ve.view_count, 0) AS view_count,
    COALESCE(ve.play_count, 0) AS play_count,
    COALESCE(ve.browse_seconds, 0) AS browse_seconds,
    ve.last_viewed_at AS last_viewed_at,
    -- Column refs (ds.count) with a zero-row FROM return an EMPTY result
    -- set -> NULL in expression context; COALESCE outside the zero-row
    -- select is not executed. Nest the row pick inside COALESCE:
    -- zero rows -> inner select yields NULL -> 0. (SUM works without the
    -- nesting because aggregates always produce one row.)
    (SELECT COALESCE((SELECT ds.count FROM daily_shown ds
        WHERE ds.asset_id = a.asset_id AND ds.day = sqlc.arg(day)), 0)) AS shown_today
FROM assets a
LEFT JOIN (
    SELECT v.asset_id AS asset_id,
           CAST(SUM(CASE WHEN v.kind = 'open' THEN 1 ELSE 0 END) AS INTEGER) AS view_count,
           CAST(SUM(CASE WHEN v.kind = 'play' THEN 1 ELSE 0 END) AS INTEGER) AS play_count,
           CAST(COALESCE(SUM(CASE WHEN v.kind = 'dwell' THEN v.seconds ELSE 0 END), 0) AS INTEGER) AS browse_seconds,
           MAX(CASE WHEN v.kind = 'open' THEN v.started_at END) AS last_viewed_at
    FROM view_events v
    GROUP BY v.asset_id
) ve ON ve.asset_id = a.asset_id
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

-- name: ListAssetsRecommendCandidateInput :many
WITH candidates AS (
    SELECT a.asset_id, a.library_id, a.rel_path, a.file_name, a.media_type,
           a.size_bytes, a.mtime, a.duration_ms, a.source, a.created_at
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
)
SELECT
    a.asset_id, a.library_id, a.rel_path, a.file_name, a.media_type,
    a.size_bytes, a.mtime, a.duration_ms, a.source, a.created_at,
    EXISTS(SELECT 1 FROM favorites fv WHERE fv.asset_id = a.asset_id) AS is_favorite,
    (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count,
    COALESCE(ve.view_count, 0) AS view_count,
    COALESCE(ve.play_count, 0) AS play_count,
    COALESCE(ve.browse_seconds, 0) AS browse_seconds,
    ve.last_viewed_at AS last_viewed_at,
    -- Column refs (ds.count) with a zero-row FROM return an EMPTY result
    -- set -> NULL in expression context; COALESCE outside the zero-row
    -- select is not executed. Nest the row pick inside COALESCE:
    -- zero rows -> inner select yields NULL -> 0. (SUM works without the
    -- nesting because aggregates always produce one row.)
    (SELECT COALESCE((SELECT ds.count FROM daily_shown ds
        WHERE ds.asset_id = a.asset_id AND ds.day = sqlc.arg(day)), 0)) AS shown_today
FROM candidates a
LEFT JOIN (
    SELECT v.asset_id AS asset_id,
           CAST(SUM(CASE WHEN v.kind = 'open' THEN 1 ELSE 0 END) AS INTEGER) AS view_count,
           CAST(SUM(CASE WHEN v.kind = 'play' THEN 1 ELSE 0 END) AS INTEGER) AS play_count,
           CAST(COALESCE(SUM(CASE WHEN v.kind = 'dwell' THEN v.seconds ELSE 0 END), 0) AS INTEGER) AS browse_seconds,
           MAX(CASE WHEN v.kind = 'open' THEN v.started_at END) AS last_viewed_at
    FROM view_events v
    WHERE v.asset_id IN (SELECT asset_id FROM candidates)
    GROUP BY v.asset_id
) ve ON ve.asset_id = a.asset_id
ORDER BY a.asset_id;

-- name: CountRecommendScope :one
-- This observation selects an execution plan only. The subsequent input
-- query MUST re-evaluate eligibility: scope must never truncate results.
SELECT COUNT(*) AS total_assets,
       CAST(COALESCE(SUM(CASE WHEN EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    AND ((sqlc.arg(cos_only) = 1 AND EXISTS (
              SELECT 1 FROM asset_authors aacos
              JOIN authors aucos ON aucos.id = aacos.author_id
              WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos'))
         OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
              SELECT 1 FROM asset_authors aa
              JOIN authors au ON au.id = aa.author_id
              WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))) THEN 1 ELSE 0 END), 0) AS INTEGER) AS candidate_assets
FROM assets a;

-- Windowed ranking heat inputs (DOMAIN_RULES 2, 2026-09-18): period
-- rankings aggregate per-day data inside the window instead of lifetime
-- totals. Both queries take fromDay = local calendar day (YYYY-MM-DD) of
-- now - window; day >= fromDay includes the whole cutoff day (day-level
-- bucketing is the finest granularity the two tables have).
--
-- Shared window aggregates are also retained as the independent legacy
-- IO oracle in ranking tests. Production ranking.LoadPage uses the
-- specialized ranking.sql projection and page-only lifetime counters.
-- Rows are asset-keyed and do not enforce library/COS eligibility here;
-- consumers must intersect with the complete eligible candidate scope.

-- SumWindowedDailyStats: per-asset window sums from the "asset x day"
-- materialized table (cache of the view_events stream; DOMAIN_RULES 5).
-- name: SumWindowedDailyStats :many
SELECT asset_daily_stats.asset_id AS asset_id,
       CAST(SUM(asset_daily_stats.view_count) AS INTEGER) AS view_count,
       CAST(SUM(asset_daily_stats.play_count) AS INTEGER) AS play_count
FROM asset_daily_stats
WHERE asset_daily_stats.day >= sqlc.arg(from_day)
GROUP BY asset_daily_stats.asset_id;

-- CountWindowedLikes: per-asset window like counts. likes is keyed
-- (asset_id, day) (one like per asset per day, DOMAIN_RULES 5), so the
-- table has native day granularity -- window likes = row count with
-- day >= fromDay.
-- name: CountWindowedLikes :many
SELECT likes.asset_id AS asset_id,
       CAST(COUNT(*) AS INTEGER) AS like_count
FROM likes
WHERE likes.day >= sqlc.arg(from_day)
GROUP BY likes.asset_id;
