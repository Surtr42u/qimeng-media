-- 0026_device_apply_verification: device apply post-apply verification.
-- Stored in a separate table instead of a new column on device_apply_tasks:
-- existing binaries read that table with SELECT * and a fixed scan list, so a
-- new column would break rollback (scan mismatch) even though the change is
-- additive. A new table is ignored by old code and keeps rollback safe.
-- The snapshot is best-effort evidence, not part of the idempotent result
-- payload (result_json stays the replay-comparison source of truth).
CREATE TABLE device_apply_verifications (
    task_id           TEXT PRIMARY KEY REFERENCES device_apply_tasks(id) ON DELETE CASCADE,
    verification_json TEXT NOT NULL CHECK (json_valid(verification_json)),
    created_at        TEXT NOT NULL
) WITHOUT ROWID;
