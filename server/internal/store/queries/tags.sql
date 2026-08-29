-- tags.sql: tag pool, asset tag bindings, timeline tags (DOMAIN_RULES 7).
-- Cascade semantics via FKs: deleting a tags row clears asset_tags refs;
-- deleting an assets row clears bindings and timeline tags (0001_init.up.sql).

-- name: ListTags :many
-- Tag pool + per-tag file count (LEFT JOIN keeps zero-ref tags).
-- Order: name ASC -- filter panels and other non-detail scenes use
-- name order (LEGACY_REQUIREMENTS A: only the detail-page tag popup
-- uses association-time order, which is ListAssetTagRefs in browse.sql).
SELECT t.id, t.name, COUNT(at.asset_id) AS file_count
FROM tags t
LEFT JOIN asset_tags at ON at.tag_id = t.id
GROUP BY t.id
ORDER BY t.name;

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
-- created_at = association time: the replace-style PUT re-inserts every
-- row, so re-adding a tag bumps it to the top of the detail-page list
-- (LEGACY_REQUIREMENTS A; column added in 0004).
INSERT INTO asset_tags (asset_id, tag_id, created_at) VALUES (?, ?, ?);

-- name: ListAllAssetTags :many
-- Full-library tag map (asset_id -> tag names) consumed by the
-- recommendation algorithm's tag relevance/collection scoring.
SELECT at.asset_id, t.name
FROM asset_tags at
JOIN tags t ON t.id = at.tag_id
ORDER BY at.asset_id, t.name;

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
