-- view_events queries (adr/0005: append-only, aggregate-only --
-- UPDATE/DELETE must never appear in this file).
-- ASCII-only comments here; see assets.sql header note and
-- migrations/0001_init.up.sql for Chinese explanations.

-- InsertViewEvent: the only write entry point. Session-level dedup
-- (one open/play per session, DOMAIN_RULES 5) is decided server-side
-- BEFORE the insert; the table has no unique constraint on purpose
-- (adr/0005: scoring rules live in one place, not hidden in the schema).

-- name: InsertViewEvent :exec
INSERT INTO view_events (asset_id, kind, session_id, started_at, seconds)
VALUES (?, ?, ?, ?, ?);

-- InsertViewEventIdempotent: write entry for the live report endpoint
-- (task L / batch L5, 2026-09-09). client_event_id is the client
-- idempotency key (unique index, migration 0010): ON CONFLICT DO
-- NOTHING turns a duplicate submission into a 0-row write, and the
-- caller (RowsAffected == 0) answers 202 WITHOUT touching the
-- materialized table -- dwell seconds are not re-accumulated, open/play
-- not re-counted, which is what lets clients delete their local pending
-- copy only after a confirmed 2xx send. A NULL id (legacy-format
-- requests, backup-import replay) never conflicts -- SQLite unique
-- indexes treat NULLs as distinct -- so old events still insert
-- normally; idempotency applies only to id-bearing events. Append-only
-- discipline intact: no UPDATE/DELETE, DO NOTHING merely skips the
-- insert (the first submission always wins, byte-for-byte).

-- name: InsertViewEventIdempotent :execrows
INSERT INTO view_events (asset_id, kind, session_id, started_at, seconds, client_event_id)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (client_event_id) DO NOTHING;

-- CountAssetEvents: per-asset view/play counts (DOMAIN_RULES 5:
-- aggregated from the event stream; dwell is not counted). At most two
-- rows per asset (open/play) -- the caller assembles them. Empty result
-- means never opened/played, not an error.

-- name: CountAssetEvents :many
SELECT asset_id, kind, COUNT(*) AS cnt
FROM view_events
WHERE asset_id = ? AND kind IN ('open', 'play')
GROUP BY asset_id, kind;

-- AggregateAllEventCounts: whole-library view/play aggregation --
-- the recompute source for daily materialized tables (adr/0005 cache)
-- and leaderboard heat. On scoring-rule changes, rerun this query over
-- the full history; the event stream itself never changes.

-- name: AggregateAllEventCounts :many
SELECT asset_id, kind, COUNT(*) AS cnt
FROM view_events
WHERE kind IN ('open', 'play')
GROUP BY asset_id, kind;

-- ExistsViewEventOnDay: session-level dedup check (DOMAIN_RULES 5 --
-- one open/play per asset+kind+session within the same local calendar
-- day). dayStart/dayEnd are the UTC timestamp strings (store format,
-- migrations/0001 header) of the event's local day 00:00 and next-day
-- 00:00; the RFC3339 TEXT layout keeps lexicographic order ==
-- chronological order, so the two-sided bound is exact. dwell never
-- consults this query: dwell seconds always accumulate.

-- name: ExistsViewEventOnDay :one
SELECT COUNT(*) FROM view_events
WHERE asset_id = ? AND kind = ? AND session_id = ?
  AND started_at >= ? AND started_at < ?;

-- CountOpenEventsAll: lifetime open count (stats overview totalViews).
-- view_events has no FK and may reference deleted assets (adr/0005);
-- counting the stream directly is intentional -- deleted-asset history
-- still counts as real views.

-- name: CountOpenEventsAll :one
SELECT COUNT(*) FROM view_events WHERE kind = 'open';

-- CountOpenEventsOnDay: open count within [dayStart, dayEnd) timestamp
-- strings (stats overview todayViews; bounds built by the caller from
-- the server clock's local calendar day, same format as started_at).

-- name: CountOpenEventsOnDay :one
SELECT COUNT(*) FROM view_events
WHERE kind = 'open' AND started_at >= ? AND started_at < ?;

-- ListAllViewEvents: full-stream scan for the materialized-table
-- rebuild (httpapi.RebuildAssetDailyStatsFromEvents). Append-only
-- table, so id order == insertion order; seconds is NULL for open/play
-- and only meaningful for dwell.

-- name: ListAllViewEvents :many
SELECT asset_id, kind, session_id, started_at, seconds
FROM view_events
ORDER BY id;

-- ============ Stats top-lists (protocol batch P2, 2026-09-09) ============
-- Windowed per-asset/author/tag aggregation straight from the event
-- stream (single source of truth, DOMAIN_RULES 5). Window semantics:
-- from_ts = RFC3339 timestamp string (store layout) of the window's
-- first local-calendar-day 00:00; empty string = no window (range=all),
-- same sentinel as media_type in daily_stats.sql. INNER JOIN assets on
-- purpose: the ranking must render file info, so deleted-asset events
-- (no FK, adr/0005) cannot appear here even though the overview still
-- counts them. Tie-break = primary key ascending for stable ordering.

-- TopOpenAssets: most-viewed by open count within the window.

-- name: TopOpenAssets :many
SELECT v.asset_id AS asset_id,
       a.file_name AS file_name,
       a.media_type AS media_type,
       CAST(COUNT(*) AS INTEGER) AS value
FROM view_events v
JOIN assets a ON a.asset_id = v.asset_id
WHERE v.kind = 'open'
  AND (CAST(sqlc.arg(from_ts) AS TEXT) = '' OR v.started_at >= sqlc.arg(from_ts))
GROUP BY v.asset_id, a.file_name, a.media_type
ORDER BY value DESC, v.asset_id
LIMIT sqlc.arg(lim);

-- TopDwellAssets: most-viewed by accumulated dwell seconds; files with
-- no dwell event in the window simply do not appear (DOMAIN_RULES 5).

-- name: TopDwellAssets :many
SELECT v.asset_id AS asset_id,
       a.file_name AS file_name,
       a.media_type AS media_type,
       CAST(COALESCE(SUM(v.seconds), 0) AS INTEGER) AS value
FROM view_events v
JOIN assets a ON a.asset_id = v.asset_id
WHERE v.kind = 'dwell'
  AND (CAST(sqlc.arg(from_ts) AS TEXT) = '' OR v.started_at >= sqlc.arg(from_ts))
GROUP BY v.asset_id, a.file_name, a.media_type
ORDER BY value DESC, v.asset_id
LIMIT sqlc.arg(lim);

-- TopOpenAuthors: per-author open count within the window. Many-to-many
-- asset x author: one open counts once for EVERY linked author. COS
-- authors are counted via their COS-linked assets only (asset_authors
-- holds no regular links for them -- prefix isolation, DOMAIN_RULES 6).

-- name: TopOpenAuthors :many
SELECT au.id AS author_id,
       au.display_name AS display_name,
       CAST(COUNT(*) AS INTEGER) AS views
FROM view_events v
JOIN assets a ON a.asset_id = v.asset_id
JOIN asset_authors aa ON aa.asset_id = a.asset_id
JOIN authors au ON au.id = aa.author_id
WHERE v.kind = 'open'
  AND (CAST(sqlc.arg(from_ts) AS TEXT) = '' OR v.started_at >= sqlc.arg(from_ts))
GROUP BY au.id, au.display_name
ORDER BY views DESC, au.id
LIMIT sqlc.arg(lim);

-- TopOpenTags: per-tag open count within the window (tagged assets).

-- name: TopOpenTags :many
SELECT t.name AS tag,
       CAST(COUNT(*) AS INTEGER) AS views
FROM view_events v
JOIN assets a ON a.asset_id = v.asset_id
JOIN asset_tags atg ON atg.asset_id = a.asset_id
JOIN tags t ON t.id = atg.tag_id
WHERE v.kind = 'open'
  AND (CAST(sqlc.arg(from_ts) AS TEXT) = '' OR v.started_at >= sqlc.arg(from_ts))
GROUP BY t.id, t.name
ORDER BY views DESC, t.name
LIMIT sqlc.arg(lim);

-- SummarizeOpenWindow: numerator/denominator pair for the overview
-- avgViewsPerFile field -- total opens vs DISTINCT assets with at least
-- one open in the window (both restricted to live assets, DOMAIN_RULES
-- 5: denominator 0 -> null is decided by the caller).

-- name: SummarizeOpenWindow :one
SELECT CAST(COUNT(*) AS INTEGER) AS opens,
       CAST(COUNT(DISTINCT v.asset_id) AS INTEGER) AS files
FROM view_events v
JOIN assets a ON a.asset_id = v.asset_id
WHERE v.kind = 'open'
  AND (CAST(sqlc.arg(from_ts) AS TEXT) = '' OR v.started_at >= sqlc.arg(from_ts));
