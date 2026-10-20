-- name: TopOpenTags :many

SELECT t.name AS tag,
       CAST(COUNT(*) AS INTEGER) AS views
FROM view_events v
JOIN assets a ON a.asset_id = v.asset_id
JOIN asset_tags atg ON atg.asset_id = a.asset_id
JOIN tags t ON t.id = atg.tag_id
WHERE v.kind = 'open'
  AND (CAST(?1 AS TEXT) = '' OR v.started_at >= ?1)
GROUP BY t.id, t.name
ORDER BY views DESC, t.name
LIMIT ?2
