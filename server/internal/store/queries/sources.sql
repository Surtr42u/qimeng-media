-- sources.sql: source-group file counts for GET /api/v1/sources
-- (albums-by-source grouping; DOMAIN_RULES 3/6).
-- ASCII-only comments here; see browse.sql header note (sqlc v1.31.1
-- multi-byte comment parser bug -- Chinese comments crash the parser).
--
-- The FROM/JOIN/filter shape mirrors the assets list (browse.sql is the
-- authoritative includeCos shape): a NULL source row = files with no
-- source group (the front-end renders an OTHER display bucket), kept as
-- a real row and NOT mapped into an ASCII literal here (the caller
-- translates the display bucket).

-- name: ListSources :many
SELECT a.source AS name, COUNT(*) AS file_count
FROM assets a
WHERE (sqlc.arg(include_cos) = 1 OR NOT EXISTS (
    SELECT 1 FROM asset_authors aa
    JOIN authors au ON au.id = aa.author_id
    WHERE aa.asset_id = a.asset_id AND au.type = 'cos'))
GROUP BY a.source
ORDER BY file_count DESC, name;
