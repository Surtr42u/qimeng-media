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

-- ListLibraryRelinkSamples: fingerprint samples for auto-relink (ADR-0025).
-- When a library root disappears from disk, the scanner compares a handful
-- of known assets (relative path + exact byte size) against candidate
-- directories under the old root's parent. Smallest files first: they are
-- the cheapest to stat and the most likely to be unique content.
-- Row cap is the relinkSampleCount constant in server/internal/scanner.

-- name: ListLibraryRelinkSamples :many
SELECT rel_path, size_bytes FROM assets
WHERE library_id = ?
ORDER BY size_bytes ASC
LIMIT ?;

-- ListCosAssetsWithoutAuthor: zero-author-link assets of one library, the
-- scan-end self-heal input (scanner enrich.go relinkOrphanCosAssets). A
-- COS-library asset MUST carry its directory author link -- the stream
-- isolation predicate (DOMAIN_RULES 6) tests the cos-author link, so a
-- linkless asset leaks into regular streams AND vanishes from the COS
-- tab. Partial states were possible because ingest (UpsertAsset) and the
-- link (AddAssetAuthor) run as separate statements: process death or a
-- single-statement failure between them left a permanent leaker that
-- periodic scans never heal (unchanged files skip re-ingest entirely).
-- rel_path containing '/' = has an author directory segment; root-level
-- files are legitimately linkless (ingestCosFile semantics), excluded here.
-- name: ListCosAssetsWithoutAuthor :many
SELECT asset_id, rel_path FROM assets
WHERE library_id = ?
  AND instr(rel_path, '/') > 0
  AND NOT EXISTS (SELECT 1 FROM asset_authors aa WHERE aa.asset_id = assets.asset_id);
