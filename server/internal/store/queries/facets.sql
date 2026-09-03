-- facets.sql: album pill-bar aggregations for GET /api/v1/assets/facets.
-- Four dimensions (partition / author / character / type) following the
-- old Android app's four-row pill bar (docs/GUIDE_UI, LEGACY_REQUIREMENTS),
-- with the old "work" row replaced by "author" per user decision.
-- ASCII-only comments; see browse.sql header for the sqlc v1.31.1 parser
-- rules this file obeys (bare sqlc.arg, no CTE aliases in WHERE, parameter
-- only inside WHEN comparisons).
--
-- ============ Counting rule: exclude-self ============
-- Every query counts its own dimension while IGNORING that dimension's
-- current selection, and applies every OTHER dimension. This is standard
-- faceted-search behavior and is what the old app's cascading
-- "partition -> type -> work -> character" pills produced, generalized to
-- freely order-independent selection: picking the type first and then the
-- author yields the same candidate counts as the reverse.
--   FacetPartitionCounts  -> applies media_type/character/work/author_id/q
--   FacetAuthorCounts     -> applies partition/media_type/character/work/q
--   FacetCharacterCounts  -> applies partition/media_type/author_id/q
--   FacetCosWorkCounts    -> applies partition/media_type/author_id/q
--   FacetMediaTypeCounts  -> applies partition/character/work/author_id/q
--
-- ============ Shared predicate shapes ============
-- The COS partition predicate is byte-identical in shape to browse.sql
-- (include_cos / cos_only two-flag form) so both files stay reviewable
-- side by side; the httpapi layer maps the `partition` enum onto the two
-- flags (all -> 1/0, regular -> 0/0, cos -> 0/1). THESE COPIES MUST STAY
-- IDENTICAL TO EACH OTHER AND TO browse.sql.
-- The q predicate is likewise copied verbatim from browse.sql (FTS
-- instr/lower substring AND-combination over a JSON keyword array).

-- name: FacetPartitionCounts :one
-- Partition pill is reported in full (it excludes itself), so the two
-- branches are computed in one pass: regular = all_count - cos_count.
SELECT
    COUNT(*) AS all_count,
    COALESCE(SUM(CASE WHEN EXISTS (
        SELECT 1 FROM asset_authors aac
        JOIN authors auc ON auc.id = aac.author_id
        WHERE aac.asset_id = a.asset_id AND auc.type = 'cos')
        THEN 1 ELSE 0 END), 0) AS cos_count
FROM assets a
WHERE
    EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    AND (sqlc.narg(characters_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(characters_json)) c
        WHERE NOT EXISTS (
            SELECT 1 FROM asset_characters ac
            WHERE ac.asset_id = a.asset_id AND ac.character_name = c.value)))
    AND (sqlc.narg(cos_work) IS NULL OR a.cos_work = sqlc.narg(cos_work))
    AND (sqlc.narg(author_id) IS NULL OR EXISTS (
        SELECT 1 FROM asset_authors aa2
        WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
    AND (sqlc.narg(q_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(q_json)) qk
        WHERE NOT EXISTS (
            SELECT 1 FROM assets_fts f
            WHERE f.rowid = a.rowid
              AND instr(lower(f.all_text), lower(qk.value)) > 0)));

-- name: FacetAuthorCounts :many
-- Authors with at least one file, both kinds (regular TXT authors + COS
-- authors). key = author_id, ready to be fed back into GET /assets.
-- author_id is deliberately NOT filtered here (exclude-self).
SELECT au.id AS author_id,
       au.display_name AS author_name,
       COUNT(DISTINCT a.asset_id) AS file_count
FROM assets a
JOIN asset_authors aa ON aa.asset_id = a.asset_id
JOIN authors au ON au.id = aa.author_id
WHERE
    EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.arg(include_cos) = 1
         OR (sqlc.arg(cos_only) = 1 AND EXISTS (
             SELECT 1 FROM asset_authors aacos
             JOIN authors aucos ON aucos.id = aacos.author_id
             WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos'))
         OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
             SELECT 1 FROM asset_authors aareg
             JOIN authors aureg ON aureg.id = aareg.author_id
             WHERE aareg.asset_id = a.asset_id AND aureg.type = 'cos')))
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    AND (sqlc.narg(characters_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(characters_json)) c
        WHERE NOT EXISTS (
            SELECT 1 FROM asset_characters ac
            WHERE ac.asset_id = a.asset_id AND ac.character_name = c.value)))
    AND (sqlc.narg(cos_work) IS NULL OR a.cos_work = sqlc.narg(cos_work))
    AND (sqlc.narg(q_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(q_json)) qk
        WHERE NOT EXISTS (
            SELECT 1 FROM assets_fts f
            WHERE f.rowid = a.rowid
              AND instr(lower(f.all_text), lower(qk.value)) > 0)))
GROUP BY au.id
ORDER BY file_count DESC, au.display_name;

-- name: FacetCharacterCounts :many
-- Regular-partition character pill: canonical names produced by the
-- source matcher (asset_characters). character filter excluded (self).
SELECT ac.character_name AS character_name,
       COUNT(DISTINCT a.asset_id) AS file_count
FROM assets a
JOIN asset_characters ac ON ac.asset_id = a.asset_id
WHERE
    EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
    AND (sqlc.arg(include_cos) = 1
         OR (sqlc.arg(cos_only) = 1 AND EXISTS (
             SELECT 1 FROM asset_authors aacos
             JOIN authors aucos ON aucos.id = aacos.author_id
             WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos'))
         OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
             SELECT 1 FROM asset_authors aareg
             JOIN authors aureg ON aureg.id = aareg.author_id
             WHERE aareg.asset_id = a.asset_id AND aureg.type = 'cos')))
    AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
    AND (sqlc.narg(author_id) IS NULL OR EXISTS (
        SELECT 1 FROM asset_authors aa2
        WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
    AND (sqlc.narg(q_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(q_json)) qk
        WHERE NOT EXISTS (
            SELECT 1 FROM assets_fts f
            WHERE f.rowid = a.rowid
              AND instr(lower(f.all_text), lower(qk.value)) > 0)))
GROUP BY ac.character_name
ORDER BY file_count DESC, ac.character_name;

-- name: FacetCosWorkCounts :many
-- COS-partition character pill: the work directory name (migration 0008,
-- `author/work/file` second segment) -- old-app "COS character = work
-- name". NULL cos_work (files placed directly under the author directory)
-- is the old app's "other" bucket and is left out; the web client adds
-- the fallback pill. character/work filters excluded (self).
SELECT a.cos_work AS work_name,
       COUNT(*) AS file_count
FROM assets a
WHERE a.cos_work IS NOT NULL
  AND EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
  AND (sqlc.arg(include_cos) = 1
       OR (sqlc.arg(cos_only) = 1 AND EXISTS (
           SELECT 1 FROM asset_authors aacos
           JOIN authors aucos ON aucos.id = aacos.author_id
           WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos'))
       OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
           SELECT 1 FROM asset_authors aareg
           JOIN authors aureg ON aureg.id = aareg.author_id
           WHERE aareg.asset_id = a.asset_id AND aureg.type = 'cos')))
  AND (sqlc.narg(media_type) IS NULL OR a.media_type = sqlc.narg(media_type))
  AND (sqlc.narg(author_id) IS NULL OR EXISTS (
      SELECT 1 FROM asset_authors aa2
      WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
  AND (sqlc.narg(q_json) IS NULL OR NOT EXISTS (
      SELECT 1 FROM json_each(sqlc.narg(q_json)) qk
      WHERE NOT EXISTS (
          SELECT 1 FROM assets_fts f
          WHERE f.rowid = a.rowid
            AND instr(lower(f.all_text), lower(qk.value)) > 0)))
GROUP BY a.cos_work
ORDER BY file_count DESC, a.cos_work;

-- name: FacetMediaTypeCounts :many
-- Type pill: one row per media_type present, so `all` (the sum) and the
-- three buckets are derived in a single scan. media_type excluded (self).
SELECT a.media_type AS media_type,
       COUNT(*) AS file_count
FROM assets a
WHERE
    EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
  AND (sqlc.arg(include_cos) = 1
       OR (sqlc.arg(cos_only) = 1 AND EXISTS (
           SELECT 1 FROM asset_authors aacos
           JOIN authors aucos ON aucos.id = aacos.author_id
           WHERE aacos.asset_id = a.asset_id AND aucos.type = 'cos'))
       OR (sqlc.arg(cos_only) = 0 AND NOT EXISTS (
           SELECT 1 FROM asset_authors aareg
           JOIN authors aureg ON aureg.id = aareg.author_id
           WHERE aareg.asset_id = a.asset_id AND aureg.type = 'cos')))
  AND (sqlc.narg(characters_json) IS NULL OR NOT EXISTS (
      SELECT 1 FROM json_each(sqlc.narg(characters_json)) c
      WHERE NOT EXISTS (
          SELECT 1 FROM asset_characters ac
          WHERE ac.asset_id = a.asset_id AND ac.character_name = c.value)))
  AND (sqlc.narg(cos_work) IS NULL OR a.cos_work = sqlc.narg(cos_work))
  AND (sqlc.narg(author_id) IS NULL OR EXISTS (
      SELECT 1 FROM asset_authors aa2
      WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
  AND (sqlc.narg(q_json) IS NULL OR NOT EXISTS (
      SELECT 1 FROM json_each(sqlc.narg(q_json)) qk
      WHERE NOT EXISTS (
          SELECT 1 FROM assets_fts f
          WHERE f.rowid = a.rowid
            AND instr(lower(f.all_text), lower(qk.value)) > 0)))
GROUP BY a.media_type
ORDER BY file_count DESC;
