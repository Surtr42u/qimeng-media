-- 0026_device_apply_verification: device apply post-apply verification.
-- Stores the server-computed authoritative business-state snapshot and the
-- segment-hash verification separately from result_json: result_json remains
-- the client-reported canonical payload used for idempotent replay comparison,
-- so recomputing counters on a retry can never turn an identical replay into
-- STATE_CONFLICT. Nullable: old task rows keep NULL and old clients are
-- unaffected.
ALTER TABLE device_apply_tasks ADD COLUMN verification_json TEXT;
