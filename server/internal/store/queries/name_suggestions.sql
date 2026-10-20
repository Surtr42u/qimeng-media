-- Keep the scanner reconciliation read unchanged. Name suggestions need
-- every filename in the same library, including COS/disabled libraries.
-- No LIKE prefilter: whitespace/alias matching happens in the pure engine.
-- name: ListFileNamesForSuggestions :many
SELECT file_name FROM assets WHERE library_id = ?;
