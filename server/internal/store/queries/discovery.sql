-- name: ListDiscoveryCandidates :many
WITH activity AS (
    SELECT asset_id, MAX(started_at) AS last_activity_at
    FROM view_events WHERE kind IN ('open', 'play') GROUP BY asset_id
)
SELECT a.asset_id, CAST(COALESCE(e.last_activity_at, '') AS TEXT) AS last_activity_at
FROM assets a
JOIN libraries l ON l.id = a.library_id AND l.enabled = 1
LEFT JOIN activity e ON e.asset_id = a.asset_id
ORDER BY a.asset_id;

-- name: ListDiscoverySummaries :many
SELECT a.asset_id, a.file_name, a.media_type, a.size_bytes, a.mtime,
       a.created_at, a.source, a.duration_ms,
       EXISTS(SELECT 1 FROM favorites f WHERE f.asset_id = a.asset_id) AS is_favorite,
       (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count
FROM json_each(sqlc.arg(asset_ids_json)) p
JOIN assets a ON a.asset_id = p.value
JOIN libraries l ON l.id = a.library_id AND l.enabled = 1
ORDER BY CAST(p.key AS INTEGER);
