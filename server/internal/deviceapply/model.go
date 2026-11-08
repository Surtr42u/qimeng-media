package deviceapply

import (
	"encoding/json"
	"errors"
	"io"
	"time"
)

// Status is the device apply task state. Values match the OpenAPI enum
// DeviceApplyTaskStatus.
type Status string

const (
	// StatusPending: registered by the PC, waiting for the phone.
	StatusPending Status = "pending"
	// StatusClaimed: the phone has claimed the package and may download it.
	StatusClaimed Status = "claimed"
	// StatusApplied: terminal success reported by the phone.
	StatusApplied Status = "applied"
	// StatusPartial: terminal partial success reported by the phone.
	StatusPartial Status = "partial"
	// StatusFailed: terminal failure reported by the phone.
	StatusFailed Status = "failed"
	// StatusCancelled: terminal cancellation requested by the PC.
	StatusCancelled Status = "cancelled"
	// StatusExpired: terminal lazy expiry.
	StatusExpired Status = "expired"
)

// Valid reports whether s is a supported state.
func (s Status) Valid() bool {
	switch s {
	case StatusPending, StatusClaimed, StatusApplied, StatusPartial,
		StatusFailed, StatusCancelled, StatusExpired:
		return true
	default:
		return false
	}
}

// Terminal reports whether s is final.
func (s Status) Terminal() bool {
	switch s {
	case StatusApplied, StatusPartial, StatusFailed, StatusCancelled,
		StatusExpired:
		return true
	default:
		return false
	}
}

// Active reports whether segment bytes may still be downloaded.
func (s Status) Active() bool { return s == StatusPending || s == StatusClaimed }

// IsResult reports whether s is an accepted result outcome.
func (s Status) IsResult() bool {
	return s == StatusApplied || s == StatusPartial || s == StatusFailed
}

// SegmentKind is the package segment family. Values match the OpenAPI enum
// DeviceApplySegmentKind.
type SegmentKind string

const (
	// SegmentMasterTxt is the author master TXT (<= 1MiB).
	SegmentMasterTxt SegmentKind = "masterTxt"
	// SegmentV2Backup is the portable v2 backup (<= 64MiB).
	SegmentV2Backup SegmentKind = "v2Backup"
)

// Valid reports whether k is a supported segment family.
func (k SegmentKind) Valid() bool {
	return k == SegmentMasterTxt || k == SegmentV2Backup
}

// MaxBytes returns the protocol size limit for the segment family.
func (k SegmentKind) MaxBytes() int64 {
	switch k {
	case SegmentMasterTxt:
		return MaxMasterTxtBytes
	case SegmentV2Backup:
		return MaxV2BackupBytes
	default:
		return 0
	}
}

// Protocol and request limits shared by the service and the HTTP layer. The
// OpenAPI schema documents the request-facing values; these constants are the
// authoritative implementation limits.
const (
	// CurrentProtocolVersion is the only accepted manifest protocol version.
	CurrentProtocolVersion = 1
	// MaxMasterTxtBytes is the masterTxt segment limit (1MiB).
	MaxMasterTxtBytes = 1 << 20
	// MaxV2BackupBytes is the v2Backup segment limit (64MiB).
	MaxV2BackupBytes = 64 << 20
	// MaxManifestBytes is the JSON manifest size limit.
	MaxManifestBytes = 256 << 10
	// MaxExpectedReadbackBytes is one segment's expectedReadback size limit.
	MaxExpectedReadbackBytes = 16 << 10
	// MaxTargetDeviceIDRunes bounds the opaque target device label.
	MaxTargetDeviceIDRunes = 100
	// MinTTLSeconds / MaxTTLSeconds bound the package lifetime.
	MinTTLSeconds = 300
	MaxTTLSeconds = 7 * 24 * 60 * 60
	// DefaultTTLSeconds applies when the manifest omits ttlSeconds.
	DefaultTTLSeconds = 24 * 60 * 60
	// DefaultListLimit / MaxListLimit bound the list page size.
	DefaultListLimit = 50
	MaxListLimit     = 100
	// MaxReadbackBytes bounds the result readback JSON object.
	MaxReadbackBytes = 64 << 10
	// MaxDeviceLocalHashes bounds the result deviceLocalHashes array.
	MaxDeviceLocalHashes = 2
	// MaxResultMessageRunes bounds the optional result message.
	MaxResultMessageRunes = 2000
)

// Manifest is the PC-supplied package manifest carried as a multipart text
// field. Segment bytes are uploaded as separate multipart file fields named
// after their kind.
type Manifest struct {
	TargetDeviceID   string            `json:"targetDeviceId"`
	ProtocolVersion  int               `json:"protocolVersion"`
	RulesFingerprint string            `json:"rulesFingerprint"`
	TTLSeconds       int               `json:"ttlSeconds,omitempty"`
	Segments         []ManifestSegment `json:"segments"`
	// ExpectedState 是应用后的权威业务状态绝对计数（可选）。键限定为
	// AuthoritativeStateKeys；服务端在回传结果时与库内真实计数比较。
	ExpectedState map[string]int64 `json:"expectedState,omitempty"`
}

// ManifestSegment declares one segment's kind, byte count, SHA-256 and
// optional expected readback metrics.
type ManifestSegment struct {
	Kind             SegmentKind     `json:"kind"`
	Bytes            int64           `json:"bytes"`
	SHA256           string          `json:"sha256"`
	ExpectedReadback json.RawMessage `json:"expectedReadback,omitempty"`
}

// Segment is one stored package segment.
type Segment struct {
	Kind             SegmentKind
	Bytes            int64
	SHA256           string
	ExpectedReadback json.RawMessage
	// RelPath is relative to the package root and always server-generated.
	RelPath string
}

// LocalHash is one phone-computed segment digest reported in the result.
type LocalHash struct {
	Kind   SegmentKind `json:"kind"`
	SHA256 string      `json:"sha256"`
}

// Result is the recorded terminal outcome.
type Result struct {
	Status            Status
	Readback          json.RawMessage
	DeviceLocalHashes []LocalHash
	Message           string
	ReportedAt        time.Time
}

// Task is one device apply ledger row plus (for details) its segments/result.
type Task struct {
	ID               string
	TargetDeviceID   string
	Status           Status
	ProtocolVersion  int
	RulesFingerprint string
	CreatedAt        time.Time
	UpdatedAt        time.Time
	ClaimedAt        *time.Time
	FinishedAt       *time.Time
	ExpiresAt        time.Time
	SegmentCount     int
	Segments         []Segment
	Result           *Result
	// Verification 是服务端在回传时计算的权威状态快照与摘要核验；旧任务
	// 或未回传结果时为 nil。
	Verification *Verification
}

// AuthoritativeStateKeys 是权威业务状态计数的全部合法键（OpenAPI
// expectedState / authoritativeState 共用）。
var AuthoritativeStateKeys = []string{
	"assets", "authors", "assetAuthorRefs", "tags", "assetTagRefs", "viewEvents",
}

// ValidAuthoritativeStateKey reports whether key is a supported count key.
func ValidAuthoritativeStateKey(key string) bool {
	switch key {
	case "assets", "authors", "assetAuthorRefs", "tags", "assetTagRefs", "viewEvents":
		return true
	default:
		return false
	}
}

// Verification 是结果回传时由服务端计算的核验快照。
type Verification struct {
	// AuthoritativeState 是回传时刻库内真实计数（绝对口径）。
	AuthoritativeState map[string]int64
	// ExpectedStateMatches 仅当 manifest 声明 expectedState 时非 nil。
	ExpectedStateMatches *bool
	// ExpectedStateDiff 仅在不一致时非空。
	ExpectedStateDiff map[string]StateDiff
	// HashVerified 仅当手机回传了 deviceLocalHashes 时非 nil；要求登记段
	// 全部回传且逐段摘要一致。
	HashVerified *bool
}

// StateDiff 是一个计数键的期望/实际差异。
type StateDiff struct {
	Expected int64 `json:"expected"`
	Actual   int64 `json:"actual"`
}

// ListOptions controls the device-addressed newest-first keyset list.
type ListOptions struct {
	TargetDeviceID string
	Status         Status
	Limit          int
	Cursor         string
}

// ListResult is one page of task summaries plus the opaque next cursor.
type ListResult struct {
	Items      []Task
	NextCursor string
}

// CreateInput is the service-level create request. Every manifest segment must
// have exactly one reader under Segments; the handler supplies the multipart
// file streams.
type CreateInput struct {
	Manifest []byte
	Segments map[SegmentKind]io.Reader
}

// ResultInput is the phone-reported terminal outcome.
type ResultInput struct {
	Status            Status
	Readback          json.RawMessage
	DeviceLocalHashes []LocalHash
	Message           string
}

// Sentinel errors mapped by httpapi to 400/404/409/500.
var (
	// ErrInvalidInput: malformed manifest, limits, cursor-independent args.
	ErrInvalidInput = errors.New("deviceapply: 参数不合法")
	// ErrNotFound: the task id does not exist.
	ErrNotFound = errors.New("deviceapply: 设备结果包任务不存在")
	// ErrSegmentNotFound: the task exists but has no such segment.
	ErrSegmentNotFound = errors.New("deviceapply: 设备结果包段不存在")
	// ErrStateConflict: the current state does not allow the operation.
	ErrStateConflict = errors.New("deviceapply: 设备结果包状态不允许该操作")
	// ErrInvalidCursor: the opaque list cursor is malformed.
	ErrInvalidCursor = errors.New("deviceapply: 分页游标不合法")
)
