-- suggestions.sql: search-box completion for GET /api/v1/search/suggestions
-- (LEGACY_REQUIREMENTS C + user decision 2026-09-05 3B, DOMAIN_RULES 3
-- matching rules). ASCII-only comments here; see assets.sql header note
-- (sqlc v1.31.1 multi-byte comment parser bug) and browse.sql header for
-- the parser rules this file obeys (bare sqlc.arg, no CTE aliases in
-- WHERE, ORDER BY only over projection aliases).
--
-- ============ Why ONE union query ============
-- The five candidate dimensions (source / character / cos author / cos
-- work / regular author) come from different tables; a single UNION keeps
-- merge + dedup + ordering + limit in SQL:
--   - UNION dedups whole rows == (type, name) pairs: same-name
--     candidates WITHIN one dimension collapse to one row; same-name
--     across dimensions each survive and the type column is exactly the
--     badge-disambiguation semantics the protocol promises.
--   - sqlc.arg(q) repeats in every branch and collapses into ONE bound
--     parameter (named-arg unification).
--   - instr(lower(x), lower(sqlc.arg(q))) is the same ASCII-folding
--     substring match as the browse q filter; % / _ in user input carry
--     no LIKE magic.
--   - The regular-author branch keeps only authors with at least one
--     linked asset (LEGACY C: zero-association authors are noise for
--     completion). Trash deletion removes the assets row, so "has files"
--     is precisely EXISTS(asset_authors JOIN assets). Sources / cos works
--     skip NULLs by their IS NOT NULL guards.
--   - The UNION sits inside a FROM subquery BECAUSE SQLite forbids
--     expressions (length(name)) in a compound SELECT's ORDER BY -- only
--     output column names are allowed there (measured 2026-09-05: "1st
--     ORDER BY term does not match any column"). Wrapping turns the
--     outer block into a plain SELECT where ORDER BY length(name), name
--     ranks short names first per completion intuition, with dictionary
--     order as the deterministic tie.
-- If a future sqlc upgrade rejects this compound shape, the documented
-- fallback is five separate queries merged/sorted/deduped/limited in the
-- handler with the SAME ordering contract.

-- name: ListSearchSuggestions :many
SELECT suggestion_type, name FROM (
    SELECT 'source' AS suggestion_type, s.source AS name
    FROM assets s
    WHERE s.source IS NOT NULL
      AND instr(lower(s.source), lower(sqlc.arg(q))) > 0
    UNION
    SELECT 'character', ac.character_name
    FROM asset_characters ac
    WHERE instr(lower(ac.character_name), lower(sqlc.arg(q))) > 0
    UNION
    SELECT 'cosAuthor', au.display_name
    FROM authors au
    WHERE au.type = 'cos'
      AND instr(lower(au.display_name), lower(sqlc.arg(q))) > 0
    UNION
    SELECT 'cosWork', cw.cos_work
    FROM assets cw
    WHERE cw.cos_work IS NOT NULL
      AND instr(lower(cw.cos_work), lower(sqlc.arg(q))) > 0
    UNION
    SELECT 'author', reg.display_name
    FROM authors reg
    WHERE reg.type = 'regular'
      AND instr(lower(reg.display_name), lower(sqlc.arg(q))) > 0
      AND EXISTS (SELECT 1 FROM asset_authors aa
                  JOIN assets a2 ON a2.asset_id = aa.asset_id
                  WHERE aa.author_id = reg.id)
)
ORDER BY length(name) ASC, name ASC
LIMIT sqlc.arg(row_limit);

-- ============ Random pools (recommend=true, empty q) ============
-- The empty-state "recommended searches" mode (openapi recommend param,
-- LEGACY random semantics) draws RANDOM candidates from five name pools.
-- Shape: one query per dimension, each pool samples up to row_limit names
-- (handler passes the request limit as the per-pool cap), then the handler
-- round-robin interleaves the five pools and truncates to the request limit.
-- Why per-pool cap = the request limit (not limit/5): a library where some
-- dimensions are empty must still fill the full limit from the remaining
-- ones; mixing across dimensions is guaranteed by the handler interleave,
-- so per-pool caps beyond that would only throw candidates away.
--
-- Each pool reuses the SAME candidate set as the matching branch of
-- ListSearchSuggestions above (same WHERE conditions):
--   - source / cosWork: assets columns guarded IS NOT NULL (NULLs produce
--     no candidates);
--   - cosAuthor: authors.type = 'cos';
--   - author: authors.type = 'regular' AND EXISTS(asset_authors JOIN
--     assets) -- only authors actually linked to files qualify (trash
--     deletion removes the assets row and disqualifies the author).
-- DISTINCT dedups same-name rows WITHIN a dimension (the UNION played this
-- role in the single-query version); same names across dimensions survive
-- independently because pools are tagged with their type in the handler.
-- DISTINCT sits inside a FROM subquery and ORDER BY random() lives on the
-- outer plain SELECT: SQLite forbids non-column ORDER BY expressions on
-- DISTINCT/compound SELECTs directly (same wrapper trick as the main
-- query above, measured 2026-09-05).

-- name: RandomSourceSuggestionPool :many
SELECT name FROM (
    SELECT DISTINCT s.source AS name
    FROM assets s
    WHERE s.source IS NOT NULL
)
ORDER BY random()
LIMIT sqlc.arg(row_limit);

-- name: RandomCharacterSuggestionPool :many
SELECT name FROM (
    SELECT DISTINCT ac.character_name AS name
    FROM asset_characters ac
)
ORDER BY random()
LIMIT sqlc.arg(row_limit);

-- name: RandomCosAuthorSuggestionPool :many
SELECT name FROM (
    SELECT DISTINCT au.display_name AS name
    FROM authors au
    WHERE au.type = 'cos'
)
ORDER BY random()
LIMIT sqlc.arg(row_limit);

-- name: RandomCosWorkSuggestionPool :many
SELECT name FROM (
    SELECT DISTINCT cw.cos_work AS name
    FROM assets cw
    WHERE cw.cos_work IS NOT NULL
)
ORDER BY random()
LIMIT sqlc.arg(row_limit);

-- name: RandomAuthorSuggestionPool :many
SELECT name FROM (
    SELECT DISTINCT reg.display_name AS name
    FROM authors reg
    WHERE reg.type = 'regular'
      AND EXISTS (SELECT 1 FROM asset_authors aa
                  JOIN assets a2 ON a2.asset_id = aa.asset_id
                  WHERE aa.author_id = reg.id)
)
ORDER BY random()
LIMIT sqlc.arg(row_limit);
