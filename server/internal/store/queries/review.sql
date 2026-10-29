-- Review uses current assets and the existing local-day materialization.
-- Counts are aggregate rows only; no raw event/asset collection is loaded.

-- name: SummarizeBrowsingReview :one
WITH opened AS (
    SELECT d.asset_id, SUM(d.view_count) AS views
    FROM asset_daily_stats d JOIN assets a ON a.asset_id = d.asset_id
    WHERE d.day >= sqlc.arg(from_day) AND d.day <= sqlc.arg(to_day) AND d.view_count > 0
    GROUP BY d.asset_id
)
SELECT CAST(COALESCE(SUM(views), 0) AS INTEGER) AS view_count,
       COUNT(*) AS unique_files,
       CAST(COALESCE(SUM(CASE WHEN (SELECT MIN(first.day) FROM asset_daily_stats first
            WHERE first.asset_id = opened.asset_id AND first.view_count > 0) >= sqlc.arg(from_day)
            THEN 1 ELSE 0 END), 0) AS INTEGER) AS first_views,
       (SELECT COUNT(DISTINCT d.day) FROM asset_daily_stats d JOIN assets a ON a.asset_id = d.asset_id
        WHERE d.day >= sqlc.arg(from_day) AND d.day <= sqlc.arg(to_day) AND d.view_count > 0) AS active_days,
       CAST((SELECT COALESCE(SUM(d.view_count), 0) FROM asset_daily_stats d JOIN assets a ON a.asset_id = d.asset_id
        WHERE d.day >= sqlc.arg(previous_from_day) AND d.day < sqlc.arg(from_day)) AS INTEGER) AS previous_view_count
FROM opened;

-- Rank exact share changes with the common denominator omitted. The output
-- stays bounded even with a large vocabulary; ties use kind/id, not row order.
-- name: ListReviewInterestChanges :many
WITH activity AS (
    SELECT d.asset_id,
           SUM(CASE WHEN d.day >= sqlc.arg(from_day) THEN d.view_count ELSE 0 END) AS current_views,
           SUM(CASE WHEN d.day < sqlc.arg(from_day) THEN d.view_count ELSE 0 END) AS previous_views
    FROM asset_daily_stats d JOIN assets a ON a.asset_id = d.asset_id
    WHERE d.day >= sqlc.arg(previous_from_day) AND d.day <= sqlc.arg(to_day) AND d.view_count > 0
    GROUP BY d.asset_id
), totals AS (
    SELECT COALESCE(SUM(current_views), 0) AS current_total,
           COALESCE(SUM(previous_views), 0) AS previous_total FROM activity
), interests AS (
    SELECT 'author' AS kind, au.id AS id, au.display_name AS name,
           SUM(v.current_views) AS current_views, SUM(v.previous_views) AS previous_views
    FROM activity v JOIN asset_authors aa ON aa.asset_id = v.asset_id JOIN authors au ON au.id = aa.author_id
    GROUP BY au.id
    UNION ALL
    SELECT 'tag', t.id, t.name, SUM(v.current_views), SUM(v.previous_views)
    FROM activity v JOIN asset_tags at ON at.asset_id = v.asset_id JOIN tags t ON t.id = at.tag_id
    GROUP BY t.id
)
SELECT CAST(kind AS TEXT) AS kind, CAST(id AS TEXT) AS id, CAST(name AS TEXT) AS name,
       CAST(current_views AS INTEGER) AS current_views, CAST(previous_views AS INTEGER) AS previous_views
FROM interests CROSS JOIN totals
WHERE current_total > 0 AND previous_total > 0
  AND current_views * previous_total != previous_views * current_total
ORDER BY ABS(current_views * previous_total - previous_views * current_total) DESC, kind, id
LIMIT sqlc.arg(max_items);

-- Persisted timestamps are canonical UTC. SQLite localtime applies the
-- service host's local zone, matching the statistics local-calendar contract.
-- name: ListReviewHours :many
SELECT CAST(strftime('%H', v.started_at, 'localtime') AS INTEGER) AS hour,
       CAST(SUM(CASE WHEN v.kind = 'open' THEN 1 ELSE 0 END) AS INTEGER) AS views,
       CAST(SUM(CASE WHEN v.kind = 'dwell' THEN v.seconds ELSE 0 END) AS INTEGER) AS seconds,
       CAST(SUM(CASE WHEN v.kind = 'dwell' AND a.media_type = 'image' THEN v.seconds ELSE 0 END) AS INTEGER) AS image_seconds,
       CAST(SUM(CASE WHEN v.kind = 'dwell' AND a.media_type = 'video' THEN v.seconds ELSE 0 END) AS INTEGER) AS video_seconds,
       CAST(SUM(CASE WHEN v.kind = 'dwell' AND a.media_type = 'animated_image' THEN v.seconds ELSE 0 END) AS INTEGER) AS animated_seconds
FROM view_events v JOIN assets a ON a.asset_id = v.asset_id
WHERE v.kind IN ('open', 'dwell') AND v.started_at >= sqlc.arg(from_stamp) AND v.started_at < sqlc.arg(to_stamp)
  AND strftime('%H', v.started_at, 'localtime') IS NOT NULL
GROUP BY hour ORDER BY hour;

-- Dwell totals use the same daily materialization as the overview, including
-- days with dwell but no new open. Video dwell is not playback duration.
-- name: SummarizeReviewDuration :one
SELECT CAST(COALESCE(SUM(d.browse_seconds), 0) AS INTEGER) AS seconds,
       CAST(COALESCE(SUM(CASE WHEN a.media_type = 'image' THEN d.browse_seconds ELSE 0 END), 0) AS INTEGER) AS image_seconds,
       CAST(COALESCE(SUM(CASE WHEN a.media_type = 'video' THEN d.browse_seconds ELSE 0 END), 0) AS INTEGER) AS video_seconds,
       CAST(COALESCE(SUM(CASE WHEN a.media_type = 'animated_image' THEN d.browse_seconds ELSE 0 END), 0) AS INTEGER) AS animated_seconds
FROM asset_daily_stats d JOIN assets a ON a.asset_id = d.asset_id
WHERE d.day >= sqlc.arg(from_day) AND d.day <= sqlc.arg(to_day);
