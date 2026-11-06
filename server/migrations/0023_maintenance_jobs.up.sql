-- 0023_maintenance_jobs.up: persistent maintenance job ledger (P3 first slice).
-- Additive only: three new tables and their indexes; no existing table or index
-- is touched. Timestamps use the store-wide UTC RFC3339 millisecond format
-- (see 0001 header), so lexicographic order equals chronological order.
--
-- State machine (validated in server/internal/maintenance, CHECK is the last
-- line of defense):
--   queued -> inventory -> research -> planned -> applying -> verifying
--   -> completed, plus waiting_evidence / waiting_authorization /
--   waiting_device, paused, partial, failed, cancelled.
--
-- idempotency_key is UNIQUE forever, including after a job reaches a terminal
-- state: replaying the same key must return the original ledger row, never
-- create a second job. lease_owner/lease_until are prepared for the future
-- worker; this slice exposes no claim/heartbeat HTTP endpoint.

CREATE TABLE maintenance_jobs (
    id                  TEXT PRIMARY KEY,
    kind                TEXT NOT NULL CHECK (kind IN (
                            'asset_research', 'author_cleanup',
                            'backup_export', 'custom')),
    status              TEXT NOT NULL CHECK (status IN (
                            'queued', 'inventory', 'research', 'planned',
                            'applying', 'verifying', 'waiting_evidence',
                            'waiting_authorization', 'waiting_device',
                            'paused', 'partial', 'completed', 'failed',
                            'cancelled')),
    library_id          TEXT,
    payload_json        TEXT NOT NULL DEFAULT '{}'
                            CHECK (json_valid(payload_json)),
    input_fingerprint   TEXT NOT NULL,
    rules_version       TEXT NOT NULL DEFAULT '',
    authorization_scope TEXT NOT NULL DEFAULT '',
    budget_json         TEXT NOT NULL DEFAULT '{}'
                            CHECK (json_valid(budget_json)),
    idempotency_key     TEXT NOT NULL,
    lease_owner         TEXT,
    lease_until         TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    started_at          TEXT,
    finished_at         TEXT,
    error_code          TEXT,
    error_message       TEXT
);

-- Replay lookup and cross-job uniqueness (terminal rows are kept, so a key
-- can never be reused for a different task).
CREATE UNIQUE INDEX idx_maintenance_jobs_idempotency_key
    ON maintenance_jobs (idempotency_key);

-- List filters: status + newest-first keyset pagination.
CREATE INDEX idx_maintenance_jobs_status_created
    ON maintenance_jobs (status, created_at DESC, id DESC);
CREATE INDEX idx_maintenance_jobs_created
    ON maintenance_jobs (created_at DESC, id DESC);

-- Future worker claim scan: expired or absent lease.
CREATE INDEX idx_maintenance_jobs_lease_until
    ON maintenance_jobs (lease_until);

CREATE TABLE maintenance_steps (
    job_id      TEXT NOT NULL REFERENCES maintenance_jobs(id) ON DELETE CASCADE,
    step        TEXT NOT NULL,
    status      TEXT NOT NULL CHECK (status IN (
                    'pending', 'running', 'completed', 'failed', 'skipped')),
    attempt     INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    started_at  TEXT,
    finished_at TEXT,
    detail_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(detail_json)),
    PRIMARY KEY (job_id, step)
);

-- Detail view: latest steps first, bounded by the service.
CREATE INDEX idx_maintenance_steps_job_started
    ON maintenance_steps (job_id, started_at DESC);

CREATE TABLE maintenance_artifacts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id     TEXT NOT NULL REFERENCES maintenance_jobs(id) ON DELETE CASCADE,
    kind       TEXT NOT NULL,
    uri        TEXT NOT NULL,
    sha256     TEXT NOT NULL,
    bytes      INTEGER NOT NULL CHECK (bytes >= 0),
    created_at TEXT NOT NULL,
    -- Artifact index is append-only; the same (job, kind, uri) is recorded once.
    UNIQUE (job_id, kind, uri)
);

CREATE INDEX idx_maintenance_artifacts_job_created
    ON maintenance_artifacts (job_id, created_at DESC, id DESC);
