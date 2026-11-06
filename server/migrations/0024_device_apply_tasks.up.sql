-- 0024_device_apply_tasks.up: PC -> phone device apply package ledger (P4 first slice).
-- Additive only: two new tables and their indexes; no existing table or index
-- is touched. Timestamps use the store-wide UTC RFC3339 millisecond format
-- (see 0001 header), so lexicographic order equals chronological order.
--
-- A task represents one PC-built result package addressed to one opaque
-- target_device_id. manifest_json only stores metadata (target, protocol,
-- rules fingerprint, expected readback and the segment list); segment bytes
-- live under <dataDir>/device-apply-packages/<taskId>/ and are never written
-- to SQLite.
--
-- State machine (validated in server/internal/deviceapply; CHECK is the last
-- line of defense):
--   pending -> claimed -> applied/partial/failed
--   pending/claimed -> cancelled (PC) or expired (lazy expiry)
--   applied/partial/failed/cancelled/expired are terminal.
--
-- No pairing, no device identity validation and no Android worker in this
-- slice: target_device_id is an opaque label filled by a future pairing flow.

CREATE TABLE device_apply_tasks (
    id                TEXT PRIMARY KEY,
    target_device_id  TEXT NOT NULL,
    status            TEXT NOT NULL CHECK (status IN (
                          'pending', 'claimed', 'applied', 'partial',
                          'failed', 'cancelled', 'expired')),
    protocol_version  INTEGER NOT NULL CHECK (protocol_version > 0),
    rules_fingerprint TEXT NOT NULL,
    manifest_json     TEXT NOT NULL CHECK (json_valid(manifest_json)),
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    claimed_at        TEXT,
    finished_at       TEXT,
    expires_at        TEXT NOT NULL,
    result_json       TEXT CHECK (result_json IS NULL OR json_valid(result_json))
);

-- Phone list scan: one device, optional status filter, newest first, with
-- id as the keyset tie-breaker (matches ORDER BY created_at DESC, id DESC).
CREATE INDEX idx_device_apply_tasks_target_status_created
    ON device_apply_tasks (target_device_id, status, created_at DESC, id DESC);

-- Lazy expiry sweep.
CREATE INDEX idx_device_apply_tasks_expires
    ON device_apply_tasks (expires_at);

CREATE TABLE device_apply_segments (
    task_id  TEXT NOT NULL REFERENCES device_apply_tasks(id) ON DELETE CASCADE,
    kind     TEXT NOT NULL CHECK (kind IN ('masterTxt', 'v2Backup')),
    bytes    INTEGER NOT NULL CHECK (bytes > 0),
    sha256   TEXT NOT NULL,
    -- Path relative to <dataDir>/device-apply-packages; server-generated only.
    rel_path TEXT NOT NULL,
    PRIMARY KEY (task_id, kind)
);
