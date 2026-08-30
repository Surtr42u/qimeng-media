-- daily_shown queries: daily recommendation impression counter
-- (DOMAIN_RULES 1.4: dailyPenalty input; counter is cache-like and can
-- be dropped/rebuilt -- see table comment in migrations/0001_init.up.sql).
-- ASCII-only comments here; see assets.sql header note.

-- name: IncrementDailyShown :exec
-- day is YYYY-MM-DD in the server's local timezone (0001 header rule).
INSERT INTO daily_shown (asset_id, day, count)
VALUES (?, ?, 1)
ON CONFLICT (asset_id, day) DO UPDATE SET count = daily_shown.count + 1;
