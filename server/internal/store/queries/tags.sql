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
-- color = '' means "no color set" (protocol batch P2, 2026-09-09;
-- migration 0009 NOT NULL DEFAULT '' sentinel, DOMAIN_RULES 7).
INSERT INTO timeline_tags (id, asset_id, time_millis, name, color, created_at)
VALUES (?, ?, ?, ?, ?, ?) RETURNING *;

-- name: UpdateTimelineTag :one
-- Full replace of the mutable fields (time/name/color); scoped like the
-- delete: tagId must belong to the given asset. 0 rows = unknown
-- (assetId, tagId) pair, caller maps that to 404. Empty color clears it.
UPDATE timeline_tags
SET time_millis = ?, name = ?, color = ?
WHERE id = ? AND asset_id = ?
RETURNING *;

-- name: DeleteTimelineTag :execrows
-- Scoped delete: tagId must belong to the given asset, so a stale
-- (assetId, tagId) pair from another asset can never delete a row.
DELETE FROM timeline_tags WHERE id = ? AND asset_id = ?;

-- name: RemoveAssetTagByName :execrows
-- Per-tag unbinding (protocol batch P2, 2026-09-09): removes ONE
-- (asset, tag) association and leaves every other association's
-- created_at untouched -- unlike the replace-style PUT which re-inserts
-- all rows. Idempotent at the association level: 0 rows affected =
-- tag exists but was not attached (caller returns 204 either way;
-- tag-name existence is checked by the caller first for a 404).
DELETE FROM asset_tags
WHERE asset_id = ?
  AND tag_id = (SELECT id FROM tags WHERE name = ?);

-- name: TouchAssetTagSet :exec
-- Bump the asset's tag-set modification time (migration 0012 column,
-- DOMAIN_RULES 10 tag-set sync semantics, 2026-09-20 user decision).
-- Called by every asset_tags mutation path: replace-style PUT, single
-- unbind, tag-delete cascade (via ListAssetIDsByTag BEFORE the delete),
-- and the import's "backup newer" replace -- the last one writes the
-- BACKUP's timestamp, not now. '' sentinel (never written) = unknown
-- provenance: import falls back to union merge when either side is
-- unknown (never fabricate a version for a merged state).
UPDATE assets SET tag_set_updated_at = ? WHERE asset_id = ?;

-- name: ListAssetIDsByTag :many
-- Assets holding the tag, read BEFORE DeleteTag: the FK cascade removes
-- asset_tags rows server-side, so after the delete the join is empty and
-- the affected set is unrecoverable. Those assets' tag sets changed ->
-- each needs a TouchAssetTagSet bump (DOMAIN_RULES 10).
SELECT asset_id FROM asset_tags WHERE tag_id = ?;
