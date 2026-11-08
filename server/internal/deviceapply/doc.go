// Package deviceapply implements the PC -> phone result package ledger
// (migration 0024, ADR-0055): task rows, segment metadata, the pending ->
// claimed -> applied/partial/failed state machine, lazy expiry, and the
// package byte store under <dataDir>/device-apply-packages/<taskId>/.
//
// Responsibilities:
//   - Create validates the manifest (opaque target device label, protocol
//     version, rules fingerprint, per-segment kind/bytes/SHA-256), writes the
//     segment bytes to disk while hashing them, and only then inserts the task
//     and segment rows in one transaction.
//   - List/Get are the phone and PC read paths; Claim marks a pending task as
//     claimed; Result records one terminal outcome idempotently; Cancel moves
//     an active task to cancelled; OpenSegment streams segment bytes.
//   - Expired pending/claimed tasks are transitioned lazily by every read or
//     write path; there is no background sweeper in this slice.
//   - Segment bytes never enter SQLite: the database stores only kind, byte
//     count, SHA-256 and a server-generated relative path.
//
// Boundary: this package only depends on store/db and the standard library.
// HTTP validation/mapping lives in httpapi (ADR-0019) and the Android worker,
// pairing flow and device identity checks are outside this slice.
package deviceapply
