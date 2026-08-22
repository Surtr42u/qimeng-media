-- favorites queries (DOMAIN_RULES 7: a boolean set).
-- ASCII-only comments here; see assets.sql header note and
-- migrations/0001_init.up.sql for Chinese explanations.
--
-- Toggle semantics (SQLite has no single-statement atomic toggle):
--   inside one transaction, call RemoveFavorite first:
--     rows == 1 -> was favorited, now removed, done;
--     rows == 0 -> was not favorited, call AddFavorite, done.

-- AddFavorite: ON CONFLICT DO NOTHING -- idempotent; 0 affected rows
-- means it was already favorited.

-- name: AddFavorite :execrows
INSERT INTO favorites (asset_id, created_at) VALUES (?, ?)
ON CONFLICT (asset_id) DO NOTHING;

-- RemoveFavorite: removing a non-favorite is not an error; 0 affected
-- rows means it was not favorited.

-- name: RemoveFavorite :execrows
DELETE FROM favorites WHERE asset_id = ?;

-- IsFavorite: returns 0/1.

-- name: IsFavorite :one
SELECT COUNT(*) FROM favorites WHERE asset_id = ?;
