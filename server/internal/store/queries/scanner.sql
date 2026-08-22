-- scanner queries (M1: full-scan reconciliation -- diff / move-merge / delete).
-- ASCII-only comments here; see assets.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug) and migrations/0001_init.up.sql for
-- Chinese explanations.

-- ListAssetsByLibrary: the scanner's reconciliation source. Loads every
-- asset row of one library so the walker can (a) do change detection in
-- memory (size+mtime, adr/0004) with zero SQL round-trips per file and
-- (b) compute the disappeared-set diff at the end of a scan cycle.
-- Why a full load is fine for M1: a home library is tens of thousands
-- of rows x ~200 bytes. Revisit (keyset-chunked reads) only past ~1e6.

-- name: ListAssetsByLibrary :many
SELECT * FROM assets WHERE library_id = ?;

-- DeleteAsset: removes one asset row. FK ON DELETE CASCADE wipes
-- dependent rows (likes/favorites/tags/characters/authors/daily_shown);
-- view_events is deliberately FK-free (adr/0005) so the append-only
-- history stream survives asset deletion.

-- name: DeleteAsset :exec
DELETE FROM assets WHERE asset_id = ?;

-- MoveAssetPath: the move-merge write (adr/0004). Identity
-- (asset_id / created_at) is untouched; only the path attribute moves.
-- size/mtime are NOT rewritten here: the merge precondition is size and
-- mtime being IDENTICAL between the gone row and the new row, so the
-- stored values already describe the file at its new location.
-- Caller must first free the target (library_id, rel_path) unique
-- index entry (delete the freshly inserted row), otherwise the UPDATE
-- violates the unique constraint.

-- name: MoveAssetPath :one
UPDATE assets
SET rel_path = ?, file_name = ?, updated_at = ?
WHERE asset_id = ?
RETURNING *;
