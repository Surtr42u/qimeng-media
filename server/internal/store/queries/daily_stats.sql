-- daily_stats queries: "asset x day" materialized aggregation table
-- (migrations/0005_m3_stats_cos.up.sql section 3) + stats overview
-- aggregation. The event stream (view_events) is the ONLY source of
-- truth (DOMAIN_RULES 5); this table is a cache that must be derivable
-- from the stream at any time.
-- ASCII-only comments here; see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug) and migrations/0001_init.up.sql for
-- Chinese explanations.

-- UpsertAssetDailyStats: incremental delta accumulation (NOT replace).
-- open/play events add +1 to view/play; dwell adds its seconds. The
-- caller keeps the deltas aligned with the event it just inserted so
-- the materialized table stays consistent with the event stream.

-- name: UpsertAssetDailyStats :exec
INSERT INTO asset_daily_stats (asset_id, day, view_count, play_count, browse_seconds)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (asset_id, day) DO UPDATE SET
    view_count     = asset_daily_stats.view_count + excluded.view_count,
    play_count     = asset_daily_stats.play_count + excluded.play_count,
    browse_seconds = asset_daily_stats.browse_seconds + excluded.browse_seconds;

-- SumDailyStatsBetween: trend buckets data source -- per-day sums for a
-- day range (inclusive, day = YYYY-MM-DD local calendar day). mediaType
-- filter: empty string = no filter (sqlc.arg + '' sentinel, same
-- convention as the empty-string params elsewhere in this project);
-- otherwise exact assets.media_type match. Rows are ordered by day so
-- the caller can stream them into bucketing as-is.
--
-- Why no separate SumDailyStatsAll query: the stats overview counts
-- views directly from view_events (kind='open'), per DOMAIN_RULES 5
-- "event stream is the single source of truth" -- the materialized
-- table only serves trend bucketing, and the all-range trend reuses
-- SumDailyStatsBetween with an empty fromDay (day >= '' always true).
-- Adding a third aggregation path would just widen the surface that
-- can drift from the event-stream truth.

-- name: SumDailyStatsBetween :many
SELECT asset_daily_stats.day                AS day,
       CAST(SUM(asset_daily_stats.view_count) AS INTEGER)     AS view_count,
       CAST(SUM(asset_daily_stats.play_count) AS INTEGER)     AS play_count,
       CAST(SUM(asset_daily_stats.browse_seconds) AS INTEGER) AS browse_seconds
FROM asset_daily_stats
JOIN assets ON assets.asset_id = asset_daily_stats.asset_id
WHERE asset_daily_stats.day >= sqlc.arg(from_day)
  AND asset_daily_stats.day <= sqlc.arg(to_day)
  AND (CAST(sqlc.arg(media_type) AS TEXT) = '' OR assets.media_type = CAST(sqlc.arg(media_type) AS TEXT))
  AND (CAST(sqlc.arg(source_bucket) AS TEXT) = ''
       OR (sqlc.arg(source_bucket) = 'cos' AND EXISTS (
           SELECT 1 FROM asset_authors aacos
           JOIN authors aucos ON aucos.id = aacos.author_id
           WHERE aacos.asset_id = asset_daily_stats.asset_id AND aucos.type = 'cos'))
       OR (sqlc.arg(source_bucket) = 'normal' AND NOT EXISTS (
           SELECT 1 FROM asset_authors aareg
           JOIN authors aureg ON aureg.id = aareg.author_id
           WHERE aareg.asset_id = asset_daily_stats.asset_id AND aureg.type = 'cos')))
GROUP BY asset_daily_stats.day
ORDER BY asset_daily_stats.day;

-- DeleteAllAssetDailyStats: wipe the materialized table before a full
-- rebuild from view_events (Go-side rebuild -- see
-- httpapi.RebuildAssetDailyStatsFromEvents for why the day bucketing
-- happens in Go, not in SQL).

-- name: DeleteAllAssetDailyStats :exec
DELETE FROM asset_daily_stats;

-- ListAllAssetIDs: current asset identities, used by the rebuild to
-- drop events whose asset was deleted (view_events has no FK, adr/0005;
-- asset_daily_stats does -- the rebuild must not insert orphan rows).

-- name: ListAllAssetIDs :many
SELECT asset_id FROM assets;

-- SummarizeAssets: stats overview library shape (total files, image
-- count with animated_image folded in per DOMAIN_RULES 11 media types,
-- video count, total size, per-type size sums). Per-type sizes keep
-- animated_image as its own key (physical footprint accounting: the
-- three keys sum to total_size_bytes exactly). COALESCE keeps empty-
-- library SUMs at 0 instead of NULL so the handler never needs null
-- handling.

-- name: SummarizeAssets :one
SELECT
    COUNT(*) AS total_files,
    CAST(COALESCE(SUM(CASE WHEN assets.media_type IN ('image', 'animated_image') THEN 1 ELSE 0 END), 0) AS INTEGER) AS image_count,
    CAST(COALESCE(SUM(CASE WHEN assets.media_type = 'video' THEN 1 ELSE 0 END), 0) AS INTEGER) AS video_count,
    CAST(COALESCE(SUM(assets.size_bytes), 0) AS INTEGER) AS total_size_bytes,
    CAST(COALESCE(SUM(CASE WHEN assets.media_type = 'image' THEN assets.size_bytes ELSE 0 END), 0) AS INTEGER) AS image_size_bytes,
    CAST(COALESCE(SUM(CASE WHEN assets.media_type = 'video' THEN assets.size_bytes ELSE 0 END), 0) AS INTEGER) AS video_size_bytes,
    CAST(COALESCE(SUM(CASE WHEN assets.media_type = 'animated_image' THEN assets.size_bytes ELSE 0 END), 0) AS INTEGER) AS animated_image_size_bytes
FROM assets;

-- SummarizeSourceSizes: overview per-source size sums (protocol batch
-- 2026-09-14). Predicates mirror CountCosLinkedAssets / CountRegularAssets
-- (DOMAIN_RULES 6 partition, no new criteria): normal = no cos author
-- linked, cos = at least one cos author linked. The two columns sum to
-- total_size_bytes (both branch on the same rows). One query instead of
-- two -- same scan, two conditional SUMs.

-- name: SummarizeSourceSizes :one
SELECT
    CAST(COALESCE(SUM(CASE WHEN NOT EXISTS (
        SELECT 1 FROM asset_authors aanorm
        JOIN authors aunorm ON aunorm.id = aanorm.author_id
        WHERE aanorm.asset_id = a.asset_id AND aunorm.type = 'cos')
        THEN a.size_bytes ELSE 0 END), 0) AS INTEGER) AS normal_size_bytes,
    CAST(COALESCE(SUM(CASE WHEN EXISTS (
        SELECT 1 FROM asset_authors aacos
        JOIN authors aucos ON aucos.id = aacos.author_id
        WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos')
        THEN a.size_bytes ELSE 0 END), 0) AS INTEGER) AS cos_size_bytes
FROM assets a;

-- CountCosLinkedAssets / CountRegularAssets: overview source inventory
-- (protocol batch P2, 2026-09-09). The two predicates mirror the browse
-- partition switch (DOMAIN_RULES 6): cos = linked to at least one cos
-- author, regular = linked to none. normal_count + cos_count == total
-- files (both scan assets directly, so the sum is exact).

-- name: CountCosLinkedAssets :one
SELECT CAST(COUNT(*) AS INTEGER) FROM assets a
WHERE EXISTS (
    SELECT 1 FROM asset_authors aacos
    JOIN authors aucos ON aucos.id = aacos.author_id
    WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos');

-- name: CountRegularAssets :one
SELECT CAST(COUNT(*) AS INTEGER) FROM assets a
WHERE NOT EXISTS (
    SELECT 1 FROM asset_authors aa
    JOIN authors au ON au.id = aa.author_id
    WHERE aa.asset_id = a.asset_id AND au.type = 'cos');
