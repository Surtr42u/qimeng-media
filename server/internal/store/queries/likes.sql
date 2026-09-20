-- likes queries (DOMAIN_RULES 5: one like per asset per day, resets
-- next day, lifetime total kept forever).
-- ASCII-only comments here; see assets.sql header note and
-- migrations/0001_init.up.sql for Chinese explanations.

-- AddLikeOnDayIdempotent: ON CONFLICT DO NOTHING insert (audit R10,
-- 2026-09-20). The old strict INSERT turned a concurrent double-click
-- (both pass HasLikedOnDay=0) into a spurious 500 for the loser: the
-- PK (asset_id, day) rejection now resolves as a 0-row write instead;
-- rows==0 tells the caller the race was lost ("already liked by the
-- winner") and the LikeState math reports liked=true. The "once per
-- day" rule stays enforced at the database layer -- a lost race can
-- never double-count.

-- name: AddLikeOnDayIdempotent :execrows
INSERT INTO likes (asset_id, day, created_at) VALUES (?, ?, ?)
ON CONFLICT (asset_id, day) DO NOTHING;

-- HasLikedOnDay: today's like status (0/1). day is YYYY-MM-DD in the
-- server's local timezone (see migration header "day" convention).

-- name: HasLikedOnDay :one
SELECT COUNT(*) FROM likes WHERE asset_id = ? AND day = ?;

-- CountAssetLikes: lifetime like count (all history rows).

-- name: CountAssetLikes :one
SELECT COUNT(*) FROM likes WHERE asset_id = ?;

-- ListLikedTodayForAssets: batch form of HasLikedOnDay for list pages --
-- the asset_ids among the given set that already have a like row on the
-- given day. Same table and same day convention as HasLikedOnDay
-- (day is YYYY-MM-DD in the server's local timezone). asset_ids_json is
-- a JSON array consumed by json_each -- same parameter shape as
-- ListAuthorNamesForAssets (see browse.sql header for the sqlc parser
-- constraints that dictate it).
-- name: ListLikedTodayForAssets :many
SELECT DISTINCT asset_id FROM likes
WHERE day = ? AND asset_id IN (SELECT value FROM json_each(sqlc.narg(asset_ids_json)));
