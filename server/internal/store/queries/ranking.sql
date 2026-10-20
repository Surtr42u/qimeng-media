-- Ranking keys only: preserve complete eligibility and asset_id input order.
-- Page metadata and lifetime card counters are loaded after pure Top-K.
-- name: ListRankingAllInput :many
SELECT a.asset_id, a.mtime,
       CAST(COALESCE(ve.view_count, 0) AS INTEGER) AS view_count,
       CAST(COALESCE(ve.play_count, 0) AS INTEGER) AS play_count,
       (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count
FROM assets a
LEFT JOIN (
    SELECT v.asset_id,
           SUM(CASE WHEN v.kind = 'open' THEN 1 ELSE 0 END) AS view_count,
           SUM(CASE WHEN v.kind = 'play' THEN 1 ELSE 0 END) AS play_count
    FROM view_events v
    GROUP BY v.asset_id
) ve ON ve.asset_id = a.asset_id
WHERE EXISTS (SELECT 1 FROM libraries le WHERE le.id = a.library_id AND le.enabled = 1)
  AND NOT EXISTS (SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
                  WHERE aa.asset_id = a.asset_id AND au.type = 'cos')
ORDER BY a.asset_id;

-- Window heat is from daily materialization and likes, never lifetime events.
-- Zero-heat candidates do not qualify; full counters remain page-only below.
-- name: ListRankingWindowInput :many
SELECT a.asset_id, a.mtime,
       CAST(COALESCE(ds.view_count, 0) AS INTEGER) AS view_count,
       CAST(COALESCE(ds.play_count, 0) AS INTEGER) AS play_count,
       CAST(COALESCE(wl.like_count, 0) AS INTEGER) AS like_count
FROM assets a
LEFT JOIN (
    SELECT d.asset_id, SUM(d.view_count) AS view_count, SUM(d.play_count) AS play_count
    FROM asset_daily_stats d WHERE d.day >= sqlc.arg(from_day) GROUP BY d.asset_id
) ds ON ds.asset_id = a.asset_id
LEFT JOIN (
    SELECT l.asset_id, COUNT(*) AS like_count
    FROM likes l WHERE l.day >= sqlc.arg(from_day) GROUP BY l.asset_id
) wl ON wl.asset_id = a.asset_id
WHERE EXISTS (SELECT 1 FROM libraries le WHERE le.id = a.library_id AND le.enabled = 1)
  AND NOT EXISTS (SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
                  WHERE aa.asset_id = a.asset_id AND au.type = 'cos')
  AND (COALESCE(ds.view_count, 0) + COALESCE(ds.play_count, 0) + COALESCE(wl.like_count, 0)) > 0
ORDER BY a.asset_id;

-- Ordered IDs are parameterized JSON, not SQL interpolation. Recheck access:
-- removed/disabled/COS-reassigned assets between reads must not leak into pages.
-- name: ListRankingPageSummaries :many
SELECT a.asset_id, a.file_name, a.media_type, a.size_bytes, a.mtime, a.created_at, a.source,
       EXISTS(SELECT 1 FROM favorites f WHERE f.asset_id = a.asset_id) AS is_favorite,
       (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count,
       (SELECT COUNT(*) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'open') AS view_count,
       (SELECT COUNT(*) FROM view_events v WHERE v.asset_id = a.asset_id AND v.kind = 'play') AS play_count
FROM json_each(sqlc.arg(asset_ids_json)) p
JOIN assets a ON a.asset_id = p.value
WHERE EXISTS (SELECT 1 FROM libraries le WHERE le.id = a.library_id AND le.enabled = 1)
  AND NOT EXISTS (SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
                  WHERE aa.asset_id = a.asset_id AND au.type = 'cos')
ORDER BY CAST(p.key AS INTEGER);
