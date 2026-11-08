-- Author profiles are ancillary JSON in kv_settings; deleting an author must not
-- resurrect an old profile when a COS directory or regular author is recreated.
-- Rename migrates the profile before deleting the old author in the same tx.
-- No existing index is changed; only the deleted author's setting key is touched.
CREATE TRIGGER creator_profile_author_deleted AFTER DELETE ON authors
BEGIN
    DELETE FROM kv_settings WHERE key = 'creator_profile:' || OLD.id;
END;
