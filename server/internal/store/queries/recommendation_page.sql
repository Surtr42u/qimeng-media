-- Page metadata is current even when recommendation order comes from a snapshot.
-- Recheck complete eligibility after concurrent deletion/library/COS changes.
-- name: ListRecommendationPageSummaries :many
SELECT a.asset_id, a.file_name, a.media_type, a.size_bytes, a.mtime,
       a.created_at, a.source, a.duration_ms,
       EXISTS(SELECT 1 FROM favorites f WHERE f.asset_id = a.asset_id) AS is_favorite,
       (SELECT COUNT(*) FROM likes l WHERE l.asset_id = a.asset_id) AS like_count
FROM json_each(sqlc.arg(asset_ids_json)) p
JOIN assets a ON a.asset_id = p.value
WHERE EXISTS (SELECT 1 FROM libraries le WHERE le.id = a.library_id AND le.enabled = 1)
  AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
  AND ((sqlc.arg(cos_only) = 1 AND EXISTS (
      SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
      WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
    OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
      SELECT 1 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
      WHERE aa.asset_id = a.asset_id AND au.type = 'cos')))
ORDER BY CAST(p.key AS INTEGER);
