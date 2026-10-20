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
        WHERE ds.asset_id = a.asset_id AND ds.day = ?1), 0)) AS shown_today
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
    AND (?2 IS NULL OR a.media_type = ?2)
    -- COS isolation vs COS-only mode (DOMAIN_RULES 6): cos_only = 0 keeps
    -- the historical regular-stream exclusion (COS never enters regular
    -- streams); cos_only = 1 restricts candidates to COS-linked assets
    -- (home cos tab = legacy "COS recommend mode": same scoring, same
    -- daily-shown penalty). Two explicit branches keep the param strictly
    -- 0/1 two-valued (no NULL three-valued-logic rows) -- same shape as
    -- the browse partition predicate in browse.sql. Handler must always
    -- pass 0 or 1 (never NULL).
    AND ((?3 = 1 AND EXISTS (
              SELECT 1 FROM asset_authors aacos
              JOIN authors aucos ON aucos.id = aacos.author_id
              WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos'))
         OR (?3 = 0 AND NOT EXISTS (
              SELECT 1 FROM asset_authors aa
              JOIN authors au ON au.id = aa.author_id
              WHERE aa.asset_id = a.asset_id AND au.type = 'cos')))
ORDER BY a.asset_id
