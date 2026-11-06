-- Keep generation tombstones on downgrade; removing them could reuse versions.
DROP TRIGGER creator_profile_revision_deleted;
