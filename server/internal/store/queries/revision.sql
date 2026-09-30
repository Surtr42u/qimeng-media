-- library revision queries (global library-content revision counter,
-- persisted in kv_settings key "library_revision"; service logic in
-- internal/libraryrevision). Reuses the generic KV table from
-- migrations/0003_settings_kv: value stores a decimal integer as TEXT.
--
-- NOTE (why English comments here): sqlc v1.31.1's SQLite parser has a
-- column-accounting bug with multi-byte (Chinese) comment text --
-- parse failures trigger non-deterministically depending on comment
-- length. Keep comments in this directory ASCII-only (see assets.sql).

-- InitLibraryRevisionIfAbsent: one-statement baseline bootstrap. When the
-- key is missing (fresh deployment / never bumped), seed it with
-- COUNT(assets) + 1: always >= 1 so the stored value can never collide
-- with the client-side "no revision recorded yet" sentinel (0/absent),
-- and for a populated library the baseline itself already reflects the
-- existing content volume. Single INSERT..SELECT keeps check-and-seed
-- atomic without a read-modify-write transaction.
-- name: InitLibraryRevisionIfAbsent :exec
INSERT INTO kv_settings (key, value, updated_at)
SELECT ?, CAST((SELECT COUNT(*) FROM assets) + 1 AS TEXT), ?
WHERE NOT EXISTS (SELECT 1 FROM kv_settings WHERE kv_settings.key = ?);

-- IncrementLibraryRevision: atomic counter bump done entirely in SQL
-- (CAST text -> integer -> text) so concurrent writers can never lose an
-- increment. There is intentionally NO wrapping transaction here: the
-- service serializes Increment calls behind an in-process mutex and
-- reads the new value back with a separate query (see Increment in
-- internal/libraryrevision/service.go).
-- name: IncrementLibraryRevision :exec
UPDATE kv_settings
SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT),
    updated_at = ?
WHERE key = ?;
