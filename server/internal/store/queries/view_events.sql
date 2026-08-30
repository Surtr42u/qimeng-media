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
