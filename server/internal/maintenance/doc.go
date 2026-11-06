// Package maintenance implements the persistent maintenance job ledger
// (migration 0023, ADR-0054): job rows, a validated state machine, idempotency
// keys, lease fields and step/artifact indexes.
//
// Responsibilities:
//   - Create/List/Get/Pause/Resume/Cancel are the only public ledger operations
//     exposed by HTTP in this slice.
//   - Create is idempotent by key: a replay returns the existing row and never
//     creates a second job. The unique index keeps that true after terminal
//     states.
//   - State changes run in a single transaction after reading the current row;
//     terminal jobs cannot be changed. Illegal transitions return
//     ErrStateConflict.
//   - Claim/Heartbeat/Complete and the step/artifact writers are internal
//     primitives for the future worker. They have no HTTP endpoint in this
//     slice.
//
// Boundary: this package only depends on store/db and the standard library.
// Business rules for asset research, author cleanup, backup export and device
// bridging live outside the ledger; HTTP validation/mapping lives in httpapi
// (ADR-0019). It must not import httpapi.
package maintenance
