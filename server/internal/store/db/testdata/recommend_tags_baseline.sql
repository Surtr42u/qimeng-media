-- name: ListAllAssetTags :many
SELECT at.asset_id, t.name
FROM asset_tags at
JOIN tags t ON t.id = at.tag_id
ORDER BY at.asset_id, t.name
