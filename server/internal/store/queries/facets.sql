-- facets.sql: album pill-bar aggregations for GET /api/v1/assets/facets.
-- Four dimensions (partition / author / character / type) following the
-- old Android app's four-row pill bar ("all" page: partition / work /
-- character / type, LEGACY_REQUIREMENTS + user decision 2026-09-03):
--   - the "author" row is the old app's WORK row: regular files grouped
--     by SOURCE + COS files grouped by COS AUTHOR, merged per partition
--     (regular = sources only, cos = cos authors only, all = both);
--   - the "character" row merges regular characters and COS works in the
--     all partition (regular / cos keep their single-kind candidates).
-- ASCII-only comments; see browse.sql header for the sqlc v1.31.1 parser
-- rules this file obeys (bare sqlc.arg, no CTE aliases in WHERE, parameter
-- only inside WHEN comparisons).
--
-- ============ Counting rule: exclude-self ============
-- Every query counts its own dimension while IGNORING that dimension's
-- current selection, and applies every OTHER dimension. This is standard
-- faceted-search behavior and is what the old app's merged pill rows
-- produced, generalized to freely order-independent selection.
--   FacetPartitionCounts  -> applies media_type/character/work/source/q
--   FacetSourceCounts     -> applies media_type/character/work/q
--                             (source and authorId are the SAME row,
--                             excluded together)
--   FacetAuthorCounts     -> applies partition/media_type/character/work/q
--                             (source and authorId are the SAME row,
--                             excluded together)
--   FacetCharacterCounts  -> applies partition/media_type/author-or-source/q
--                             (character and work are the SAME row,
--                             excluded together)
--   FacetCosWorkCounts    -> applies partition/media_type/author-or-source/q
--                             (character and work are the SAME row,
--                             excluded together)
--   FacetMediaTypeCounts  -> applies partition/character/work/author-or-source/q
--
-- ============ Shared predicate shapes ============
-- The COS partition predicate is byte-identical in shape to browse.sql
-- (include_cos / cos_only two-flag form) so both files stay reviewable
-- side by side; the httpapi layer maps the `partition` enum onto the two
-- flags (all -> 1/0, regular -> 0/0, cos -> 0/1). THESE COPIES MUST STAY
-- IDENTICAL TO EACH OTHER AND TO browse.sql.
-- The source predicate is likewise copied verbatim from browse.sql
-- (source_is_other = the user-facing OTHER bucket: NULL source AND not
-- cos-linked -- cos assets never enter the source system, enrich.go).
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
    AND (sqlc.narg(source) IS NULL
         OR (sqlc.arg(source_is_other) = 1 AND a.source IS NULL
             AND NOT EXISTS (
                 SELECT 1 FROM asset_authors aaoth
                 JOIN authors auoth ON auoth.id = aaoth.author_id
                 WHERE aaoth.asset_id = a.asset_id AND auoth.type = 'cos'))
         OR a.source = sqlc.narg(source))
    AND (sqlc.narg(author_id) IS NULL OR EXISTS (
        SELECT 1 FROM asset_authors aa2
        WHERE aa2.asset_id = a.asset_id AND aa2.author_id = sqlc.narg(author_id)))
    AND (sqlc.narg(q_json) IS NULL OR NOT EXISTS (
        SELECT 1 FROM json_each(sqlc.narg(q_json)) qk
        WHERE NOT EXISTS (
            SELECT 1 FROM assets_fts f
            WHERE f.rowid = a.rowid
              AND instr(lower(f.all_text), lower(qk.value)) > 0)));

-- name: FacetSourceCounts :many
-- Author-row SOURCE buckets: regular (non-cos-linked) assets grouped by
-- the matcher's canonical source. The old app's groupBySource filtered
-- !isCosFile before grouping; the NULL-source group is the user-facing
-- OTHER bucket and IS returned (as a NULL row -- the handler labels it).
-- Self-excluded: source and author_id are the SAME pill row, so neither
-- is applied here.
SELECT a.source AS source_name,
       COUNT(*) AS file_count
FROM assets a
WHERE
    EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
    AND NOT EXISTS (
        SELECT 1 FROM asset_authors aas
        JOIN authors aus ON aus.id = aas.author_id
        WHERE aas.asset_id = a.asset_id AND aus.type = 'cos')
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
GROUP BY a.source
ORDER BY file_count DESC, a.source;

-- name: FacetAuthorCounts :many
-- Author-row COS-AUTHOR buckets (old app groupByCosAuthor). Restrained
-- to au.type = 'cos': regular TXT authors are NOT part of this row (user
-- decision 2026-09-03 -- the row is "cos authors + regular works").
-- key = author_id, ready to be fed back into GET /assets.
-- author_id and source are deliberately NOT filtered here (exclude-self,
-- same pill row). The partition flags are kept for shape consistency with
-- the other facet queries; with au.type='cos' the regular partition
-- naturally yields no rows (cos authors only link cos-linked assets).
SELECT au.id AS author_id,
       au.display_name AS author_name,
       COUNT(DISTINCT a.asset_id) AS file_count
FROM assets a
JOIN asset_authors aa ON aa.asset_id = a.asset_id
JOIN authors au ON au.id = aa.author_id
WHERE
    EXISTS (SELECT 1 FROM libraries le
                WHERE le.id = a.library_id AND le.enabled = 1)
    AND au.type = 'cos'
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
-- Character-row REGULAR buckets: canonical names produced by the source
-- matcher (asset_characters). character/work filters excluded (self row).
-- The source filter applies (author row selection, other dimension).
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
    AND (sqlc.narg(source) IS NULL
         OR (sqlc.arg(source_is_other) = 1 AND a.source IS NULL
             AND NOT EXISTS (
                 SELECT 1 FROM asset_authors aaoth
                 JOIN authors auoth ON auoth.id = aaoth.author_id
                 WHERE aaoth.asset_id = a.asset_id AND auoth.type = 'cos'))
         OR a.source = sqlc.narg(source))
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
-- Character-row COS buckets: the work directory name (migration 0008,
-- `author/work/file` second segment) -- old-app "COS character = work
-- name". NULL cos_work (files placed directly under the author directory)
-- is the old app's "other" bucket and is left out; the web client adds
-- the fallback pill. character/work filters excluded (self row); the
-- source filter applies (author row selection, other dimension).
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
  AND (sqlc.narg(source) IS NULL
       OR (sqlc.arg(source_is_other) = 1 AND a.source IS NULL
           AND NOT EXISTS (
               SELECT 1 FROM asset_authors aaoth
               JOIN authors auoth ON auoth.id = aaoth.author_id
               WHERE aaoth.asset_id = a.asset_id AND auoth.type = 'cos'))
       OR a.source = sqlc.narg(source))
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
-- The source filter applies (author row selection, other dimension).
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
  AND (sqlc.narg(source) IS NULL
       OR (sqlc.arg(source_is_other) = 1 AND a.source IS NULL
           AND NOT EXISTS (
               SELECT 1 FROM asset_authors aaoth
               JOIN authors auoth ON auoth.id = aaoth.author_id
               WHERE aaoth.asset_id = a.asset_id AND auoth.type = 'cos'))
       OR a.source = sqlc.narg(source))
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
