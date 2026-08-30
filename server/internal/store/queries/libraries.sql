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
