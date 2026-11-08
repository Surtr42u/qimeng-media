-- Preserve per-author revision generations after profile deletion. This closes
-- the delete/recreate ABA window for late PUT/refresh responses, including an
-- author that never had a profile. No existing index changes.
-- Seed revisions for profiles saved before this migration; deletion triggers
-- never need to read the profile and therefore do not depend on trigger order.
INSERT INTO kv_settings (key, value, updated_at)
SELECT 'creator_profile_revision:' || substr(key, length('creator_profile:') + 1),
       CAST(json_extract(value, '$.revision') AS TEXT), updated_at
FROM kv_settings
WHERE substr(key, 1, length('creator_profile:')) = 'creator_profile:'
  AND json_valid(value)
  AND json_type(value, '$.revision') = 'integer'
  AND json_extract(value, '$.revision') >= 0
ON CONFLICT(key) DO UPDATE SET
    value = CAST(max(CAST(kv_settings.value AS INTEGER), CAST(excluded.value AS INTEGER)) AS TEXT);

CREATE TRIGGER creator_profile_revision_deleted AFTER DELETE ON authors
BEGIN
    INSERT INTO kv_settings (key, value, updated_at)
    VALUES ('creator_profile_revision:' || OLD.id, '1', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    ON CONFLICT(key) DO UPDATE SET
        value = CAST(CAST(kv_settings.value AS INTEGER) + 1 AS TEXT),
        updated_at = excluded.updated_at;
END;
