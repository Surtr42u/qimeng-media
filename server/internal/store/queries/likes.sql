-- likes queries (DOMAIN_RULES 5: one like per asset per day, resets
-- next day, lifetime total kept forever).
-- ASCII-only comments here; see assets.sql header note and
-- migrations/0001_init.up.sql for Chinese explanations.

-- AddLike: plain INSERT -- a second row for the same (asset_id, day) is
-- rejected by PRIMARY KEY (asset_id, day): the "once per day" rule is
-- enforced at the database layer, so concurrency/retry double-clicks
-- cannot double-count. Check HasLikedOnDay before writing.

-- name: AddLike :exec
INSERT INTO likes (asset_id, day, created_at) VALUES (?, ?, ?);

-- HasLikedOnDay: today's like status (0/1). day is YYYY-MM-DD in the
-- server's local timezone (see migration header "day" convention).

-- name: HasLikedOnDay :one
SELECT COUNT(*) FROM likes WHERE asset_id = ? AND day = ?;

-- CountAssetLikes: lifetime like count (all history rows).

-- name: CountAssetLikes :one
SELECT COUNT(*) FROM likes WHERE asset_id = ?;
