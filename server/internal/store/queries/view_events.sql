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
