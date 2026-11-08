// Package maintenance implements the persistent maintenance job ledger
// (migration 0023, ADR-0054): job rows, a validated state machine, idempotency
// keys, lease fields and step/artifact indexes, plus the in-process worker that
// claims and executes jobs of the kinds it has a handler for.
//
// Responsibilities:
//   - Create/List/Get/Pause/Resume/Cancel are the only public ledger operations
//     exposed by HTTP in this slice.
//   - Create is idempotent by key: a replay with the same input fingerprint
//     returns the existing row and never creates a second job (created=false).
//     Reusing a key with a different kind/library/payload/rulesVersion/budget
//     returns ErrStateConflict (HTTP 409) instead of silently replaying the
//     wrong task. The unique index keeps that true after terminal states.
//   - State changes run in a single transaction after reading the current row;
//     terminal jobs cannot be changed. Illegal transitions return
//     ErrStateConflict.
//   - Claim/Heartbeat/Complete/AdvanceStatus and the step/artifact writers are
//     internal primitives for the worker. They have no HTTP endpoint: external
//     executors still cannot claim/lease/complete jobs over the API.
//   - Worker (worker.go) is started from the composition root; it only claims
//     kinds with a registered handler (backup_export in this slice), renews its
//     lease with a heartbeat, records every step, resumes from completed steps
//     after a crash/restart and finishes jobs to completed/partial/failed.
//
// Boundary: the ledger and worker machinery depend on store/db, the standard
// library, safego and (for the built-in backup_export handler only) the backup
// package that reads/writes local snapshot files. Business rules for asset
// research, author cleanup and device bridging live outside this package; the
// HTTP validation/mapping lives in httpapi (ADR-0019). It must not import
// httpapi.
package maintenance
