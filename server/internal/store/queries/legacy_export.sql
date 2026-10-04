-- legacy_export.sql: legacy-format backup export dumps (new library ->
-- qimeng_backup.json, DOMAIN_RULES S10 reverse mapping). All read-only;
-- recordKey assignment / millis conversion / history cap live in
-- httpapi/export.go, mirroring the import side matchFiles folder
-- disambiguation so an export can round-trip through
-- POST /import/qimeng-backup.
-- ASCII-only comments here; see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug).

-- ExportListAssets: full asset dump with library kind (isCosFile derives
-- from kind='cos'). Ordered by (file_name, rel_path) so the same-name
-- grouping that assigns recordKeys is deterministic.
--
-- name: ExportListAssets :many
SELECT a.asset_id         AS asset_id,
       a.file_name        AS file_name,
       a.rel_path         AS rel_path,
       a.media_type       AS media_type,
       a.size_bytes       AS size_bytes,
       a.mtime            AS mtime,
       a.duration_ms      AS duration_ms,
       a.width            AS width,
       a.height           AS height,
       a.created_at       AS created_at,
       -- tag-set mtime for the backup's tagsUpdatedAtMillis (DOMAIN_RULES 10
       -- tag-set sync semantics); '' sentinel exports as omitted field.
       a.tag_set_updated_at AS tag_set_updated_at,
       l.kind             AS library_kind
FROM assets AS a
JOIN libraries AS l ON l.id = a.library_id
ORDER BY a.file_name, a.rel_path;

-- name: ExportAuthors :many
-- origin (ADR-0032): provenance passthrough -- the backup carries each
-- author row's original creation channel so the receiving side can
-- adjudicate (DOMAIN_RULES 10); legacy = pre-0016 untraceable rows.
SELECT id, display_name, created_at, origin FROM authors ORDER BY id;

-- name: ExportFollowedAuthorIDs :many
SELECT id FROM authors WHERE followed = 1 ORDER BY id;

-- name: ExportAssetAuthors :many
-- created_at/origin (ADR-0032): provenance passthrough for the
-- author-media refs (created_at NULL = untraceable, exports as omitted
-- field; DOMAIN_RULES 10).
SELECT aa.author_id AS author_id,
       aa.asset_id  AS asset_id,
       aa.created_at AS created_at,
       aa.origin     AS origin
FROM asset_authors AS aa
ORDER BY aa.author_id, aa.asset_id;

-- name: ExportTags :many
SELECT name, created_at FROM tags ORDER BY name;

-- name: ExportAssetTags :many
-- origin (ADR-0032): provenance passthrough alongside the existing
-- created_at (DOMAIN_RULES 10).
SELECT mt.asset_id   AS asset_id,
       t.name        AS tag_name,
       mt.created_at AS created_at,
       mt.origin     AS origin
FROM asset_tags AS mt
JOIN tags AS t ON t.id = mt.tag_id
ORDER BY mt.asset_id, t.name;

-- name: ExportTimelineTags :many
SELECT t.asset_id    AS asset_id,
       t.time_millis AS time_millis,
       t.name        AS tag_name,
       t.created_at  AS created_at
FROM timeline_tags AS t
ORDER BY t.asset_id, t.time_millis, t.name;

-- ExportLikeAggregates: legacy likes section keeps a cumulative count plus
-- the last like day only (one row per asset); the new schema stores one row
-- per (asset, day), so COUNT(*) is the closest possible cumulative value.
--
-- name: ExportLikeAggregates :many
SELECT l.asset_id  AS asset_id,
       CAST(COUNT(*) AS INTEGER) AS like_count,
       MAX(l.day) AS last_day
FROM likes AS l
GROUP BY l.asset_id
ORDER BY l.asset_id;

-- name: ExportFavorites :many
SELECT f.asset_id AS asset_id
FROM favorites AS f
ORDER BY f.asset_id;

-- ExportDailyBrowse: the materialized (asset x day) table IS the daily
-- detail the legacy dailyBrowse section wants; event stream remains the
-- source of truth and this table is derived (DOMAIN_RULES S5).
--
-- name: ExportDailyBrowse :many
SELECT d.asset_id      AS asset_id,
       d.day           AS day,
       d.view_count    AS view_count,
       d.play_count    AS play_count,
       d.browse_seconds AS browse_seconds
FROM asset_daily_stats AS d
ORDER BY d.asset_id, d.day;

-- ExportEventTotals: per-asset cumulative stats for the legacy mediaStats
-- section, aggregated straight from the event stream (the truth source).
-- last_opened_at is NULL for assets that only have play/dwell events.
--
-- name: ExportEventTotals :many
SELECT v.asset_id AS asset_id,
       CAST(SUM(CASE WHEN v.kind = 'open' THEN 1 ELSE 0 END) AS INTEGER) AS view_count,
       CAST(SUM(CASE WHEN v.kind = 'play' THEN 1 ELSE 0 END) AS INTEGER) AS play_count,
       CAST(COALESCE(SUM(v.seconds), 0) AS INTEGER)                      AS browse_seconds,
       MAX(CASE WHEN v.kind = 'open' THEN v.started_at END)              AS last_opened_at,
       MAX(v.started_at)                                                 AS last_event_at
FROM view_events AS v
GROUP BY v.asset_id;

-- ExportRecentOpenEvents: history section = ALL open events, newest first.
-- 2026-10-04: user lifted the old 500-row cap (legacy view_history compat
-- truncation retired). Event rows are ~100B each; tens of thousands cost only
-- a few MB of backup payload. Full history now travels inside every backup.
-- Order does not matter to the importer. NOTE: ASCII-only comments in this
-- file (sqlc v1.31.1 multi-byte comment parser bug, see assets.sql header).
--
-- name: ExportRecentOpenEvents :many
SELECT v.asset_id   AS asset_id,
       v.started_at AS started_at
FROM view_events AS v
WHERE v.kind = 'open'
ORDER BY v.id DESC;
