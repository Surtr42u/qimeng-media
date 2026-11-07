package maintenance

import (
	"encoding/json"
	"errors"
	"time"
)

// Kind is the maintenance task family. Values match the OpenAPI enum
// MaintenanceJobKind.
type Kind string

const (
	KindAssetResearch Kind = "asset_research"
	KindAuthorCleanup Kind = "author_cleanup"
	KindBackupExport  Kind = "backup_export"
	KindCustom        Kind = "custom"
)

// Valid reports whether k is a supported task family.
func (k Kind) Valid() bool {
	switch k {
	case KindAssetResearch, KindAuthorCleanup, KindBackupExport, KindCustom:
		return true
	default:
		return false
	}
}

// Status is the ledger state. Values match the OpenAPI enum
// MaintenanceJobStatus.
type Status string

const (
	StatusQueued               Status = "queued"
	StatusInventory            Status = "inventory"
	StatusResearch             Status = "research"
	StatusPlanned              Status = "planned"
	StatusApplying             Status = "applying"
	StatusVerifying            Status = "verifying"
	StatusWaitingEvidence      Status = "waiting_evidence"
	StatusWaitingAuthorization Status = "waiting_authorization"
	StatusWaitingDevice        Status = "waiting_device"
	StatusPaused               Status = "paused"
	StatusPartial              Status = "partial"
	StatusCompleted            Status = "completed"
	StatusFailed               Status = "failed"
	StatusCancelled            Status = "cancelled"
)

// Valid reports whether s is a supported ledger state.
func (s Status) Valid() bool {
	switch s {
	case StatusQueued, StatusInventory, StatusResearch, StatusPlanned,
		StatusApplying, StatusVerifying, StatusWaitingEvidence,
		StatusWaitingAuthorization, StatusWaitingDevice, StatusPaused,
		StatusPartial, StatusCompleted, StatusFailed, StatusCancelled:
		return true
	default:
		return false
	}
}

// Terminal reports whether s is a final state. Terminal jobs can never be
// paused, resumed, cancelled or claimed again.
func (s Status) Terminal() bool {
	switch s {
	case StatusPartial, StatusCompleted, StatusFailed, StatusCancelled:
		return true
	default:
		return false
	}
}

// CanPause reports whether the public pause operation is legal.
func (s Status) CanPause() bool {
	return s.Valid() && !s.Terminal() && s != StatusPaused
}

// CanResume reports whether the public resume operation is legal. Resume is
// defined as re-queueing: the future worker restarts the pipeline at inventory.
func (s Status) CanResume() bool { return s == StatusPaused }

// CanCancel reports whether the public cancel operation is legal.
func (s Status) CanCancel() bool { return s.Valid() && !s.Terminal() }

// StepStatus is the per-step execution state. Values match the OpenAPI enum
// MaintenanceStepStatus.
type StepStatus string

const (
	StepPending   StepStatus = "pending"
	StepRunning   StepStatus = "running"
	StepCompleted StepStatus = "completed"
	StepFailed    StepStatus = "failed"
	StepSkipped   StepStatus = "skipped"
)

// Valid reports whether s is a supported step state.
func (s StepStatus) Valid() bool {
	switch s {
	case StepPending, StepRunning, StepCompleted, StepFailed, StepSkipped:
		return true
	default:
		return false
	}
}

// Input limits shared by the service and the HTTP layer. The OpenAPI schema
// documents the request-facing limits; these constants are the authoritative
// implementation values.
const (
	MaxPayloadBytes        = 64 << 10
	MaxBudgetBytes         = 16 << 10
	MaxIdempotencyKeyRunes = 200
	MaxRulesVersionRunes   = 100
	MaxLibraryIDRunes      = 100
	MaxStepNameRunes       = 100
	MaxArtifactKindRunes   = 50
	MaxArtifactURIRunes    = 500
	MaxErrorCodeRunes      = 100
	MaxErrorMessageRunes   = 2000
	MaxLeaseOwnerRunes     = 200
	DefaultListLimit       = 50
	MaxListLimit           = 100
	MaxDetailSteps         = 50
	MaxDetailArtifacts     = 100
)

// Job is the ledger row exposed by the API (plus internal fingerprint/lease
// fields used by the future worker).
type Job struct {
	ID                 string
	Kind               Kind
	Status             Status
	LibraryID          string
	Payload            json.RawMessage
	InputFingerprint   string
	RulesVersion       string
	AuthorizationScope string
	Budget             json.RawMessage
	IdempotencyKey     string
	LeaseOwner         string
	LeaseUntil         *time.Time
	CreatedAt          time.Time
	UpdatedAt          time.Time
	StartedAt          *time.Time
	FinishedAt         *time.Time
	ErrorCode          string
	ErrorMessage       string
	StepCount          int
	Progress           int
}

// Step is one indexed pipeline step. Detail is a JSON object supplied by the
// worker; the ledger treats it as opaque.
type Step struct {
	Step       string
	Status     StepStatus
	Attempt    int
	StartedAt  *time.Time
	FinishedAt *time.Time
	Detail     json.RawMessage
}

// Artifact is one indexed output reference (no bytes are stored in SQLite).
type Artifact struct {
	Kind      string
	URI       string
	SHA256    string
	Bytes     int64
	CreatedAt time.Time
}

// Detail is a job with its most recent steps and artifacts.
type Detail struct {
	Job       Job
	Steps     []Step
	Artifacts []Artifact
}

// CreateInput is the service-level create request. Payload and Budget must be
// JSON objects; nil/empty means {}.
type CreateInput struct {
	Kind           Kind
	LibraryID      string
	Payload        json.RawMessage
	IdempotencyKey string
	RulesVersion   string
	Budget         json.RawMessage
}

// ListOptions controls the newest-first keyset list.
type ListOptions struct {
	Status Status
	Limit  int
	Cursor string
}

// ListResult is one page of job summaries plus the opaque next cursor.
type ListResult struct {
	Items      []Job
	NextCursor string
}

// StepInput is the internal step-writer input (no HTTP surface).
//
// LeaseOwner is the fencing token of the claim that produced this step (worker
// sets it; see Worker.newLeaseToken). Non-empty means the write only succeeds
// while that exact owner still holds an unexpired lease; empty keeps the
// administrative/test write path unfenced.
type StepInput struct {
	JobID      string
	Step       string
	Status     StepStatus
	Attempt    int
	StartedAt  *time.Time
	FinishedAt *time.Time
	Detail     json.RawMessage
	LeaseOwner string
}

// ArtifactInput is the internal artifact-writer input (no HTTP surface).
// LeaseOwner carries the same fencing semantics as StepInput.LeaseOwner.
type ArtifactInput struct {
	JobID      string
	Kind       string
	URI        string
	SHA256     string
	Bytes      int64
	CreatedAt  time.Time
	LeaseOwner string
}

// Sentinel errors mapped by httpapi to 400/404/409.
var (
	// ErrNotFound: the job id does not exist.
	ErrNotFound = errors.New("maintenance: 维护任务不存在")
	// ErrStateConflict: the current state does not allow the operation
	// (including any change to a terminal job).
	ErrStateConflict = errors.New("maintenance: 维护任务状态不允许该操作")
	// ErrInvalidInput: a service-level argument is malformed.
	ErrInvalidInput = errors.New("maintenance: 参数不合法")
	// ErrInvalidCursor: the opaque list cursor is malformed.
	ErrInvalidCursor = errors.New("maintenance: 分页游标不合法")
	// ErrLeaseLost: the worker no longer owns a valid lease.
	ErrLeaseLost = errors.New("maintenance: 维护任务租约已失效")
)
