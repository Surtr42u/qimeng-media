-- name: TopOpenAuthors :many

SELECT au.id AS author_id,
       au.display_name AS display_name,
       CAST(COUNT(*) AS INTEGER) AS views
FROM view_events v
JOIN assets a ON a.asset_id = v.asset_id
JOIN asset_authors aa ON aa.asset_id = a.asset_id
JOIN authors au ON au.id = aa.author_id
WHERE v.kind = 'open'
  AND (CAST(?1 AS TEXT) = '' OR v.started_at >= ?1)
GROUP BY au.id, au.display_name
ORDER BY views DESC, au.id
LIMIT ?2
