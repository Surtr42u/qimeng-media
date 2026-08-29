-- tags.sql: tag pool, asset tag bindings, timeline tags (DOMAIN_RULES 7).
-- Cascade semantics via FKs: deleting a tags row clears asset_tags refs;
-- deleting an assets row clears bindings and timeline tags (0001_init.up.sql).

-- name: ListTags :many
-- Tag pool + per-tag file count (LEFT JOIN keeps zero-ref tags).
SELECT t.id, t.name, COUNT(at.asset_id) AS file_count
FROM tags t
LEFT JOIN asset_tags at ON at.tag_id = t.id
GROUP BY t.id
ORDER BY t.created_at, t.id;

-- name: CreateTag :one
INSERT INTO tags (id, name, created_at) VALUES (?, ?, ?) RETURNING *;

-- name: GetTag :one
SELECT * FROM tags WHERE id = ?;

-- name: GetTagByName :one
SELECT * FROM tags WHERE name = ?;

-- name: DeleteTag :exec
DELETE FROM tags WHERE id = ?;

-- name: DeleteAssetTags :exec
-- "Clear all" half of the replace-style PUT binding (DOMAIN_RULES 7).
DELETE FROM asset_tags WHERE asset_id = ?;

-- name: AddAssetTag :exec
INSERT INTO asset_tags (asset_id, tag_id) VALUES (?, ?);

-- name: ListTimelineTags :many
SELECT * FROM timeline_tags WHERE asset_id = ?
ORDER BY time_millis, id;

-- name: InsertTimelineTag :one
INSERT INTO timeline_tags (id, asset_id, time_millis, name, created_at)
VALUES (?, ?, ?, ?, ?) RETURNING *;

-- name: DeleteTimelineTag :execrows
-- Scoped delete: tagId must belong to the given asset, so a stale
-- (assetId, tagId) pair from another asset can never delete a row.
DELETE FROM timeline_tags WHERE id = ? AND asset_id = ?;
