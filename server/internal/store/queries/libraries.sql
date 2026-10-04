-- libraries queries (M1 minimal set: full CRUD -- libraries are
-- low-frequency admin operations).
-- ASCII-only comments here; see assets.sql header note and
-- migrations/0001_init.up.sql for Chinese explanations.

-- name: CreateLibrary :one
INSERT INTO libraries (id, name, root_path, kind, created_at) VALUES (?, ?, ?, ?, ?)
RETURNING *;

-- name: GetLibrary :one
SELECT * FROM libraries WHERE id = ?;

-- name: ListLibraries :many
SELECT * FROM libraries ORDER BY created_at DESC;

-- UpdateLibrary: rename / change root. Changing the root is an admin
-- operation: rel_path of assets is unchanged, the scanner recomputes
-- absolute paths from the new root.

-- name: UpdateLibrary :one
UPDATE libraries SET name = ?, root_path = ? WHERE id = ?
RETURNING *;

-- DeleteLibrary: cascades to all assets of this library
-- (assets.library_id ON DELETE CASCADE). Media files themselves are NOT
-- touched here -- deletion goes through the trash flow (filing module).

-- name: DeleteLibrary :exec
DELETE FROM libraries WHERE id = ?;

-- name: SetLibraryEnabled :exec
UPDATE libraries SET enabled = ? WHERE id = ?;

-- CountAllLibrariesMedia: per-library media_type counters for ALL
-- libraries in one pass. Same shape/semantics as CountLibraryMedia
-- (browse.sql) but grouped by library too, so list/metrics paths can
-- replace their per-library CountLibraryMedia loop (N+1) with a single
-- query; libraries with no assets simply have no rows here and the Go
-- side treats absent rows as 0 (same as CountLibraryMedia's contract).

-- name: CountAllLibrariesMedia :many
SELECT library_id, media_type, COUNT(*) AS cnt FROM assets
GROUP BY library_id, media_type;
