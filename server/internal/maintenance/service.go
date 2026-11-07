package maintenance

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// Options assembles the ledger service (dependency injection happens in main
// or in tests; the package never constructs a second database).
type Options struct {
	Conn    *sql.DB
	Queries *db.Queries
	Now     func() time.Time
	Logger  *slog.Logger
}

// Service is the persistent maintenance ledger.
type Service struct {
	conn   *sql.DB
	q      *db.Queries
	now    func() time.Time
	logger *slog.Logger
}

// New constructs the service. Conn and Queries are expected to be the same
// store already validated by the composition root; tests pass a migrated
// temporary SQLite database.
func New(opts Options) *Service {
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	q := opts.Queries
	if q == nil && opts.Conn != nil {
		q = db.New(opts.Conn)
	}
	return &Service{conn: opts.Conn, q: q, now: now, logger: logger}
}

// Create inserts a queued job or returns the existing row for a replayed
// idempotency key. created=false means the key already existed; the HTTP layer
// maps that to 200 instead of 201.
func (s *Service) Create(ctx context.Context, in CreateInput) (Detail, bool, error) {
	kind := Kind(strings.TrimSpace(string(in.Kind)))
	if !kind.Valid() {
		return Detail{}, false, fmt.Errorf("%w: kind 不合法", ErrInvalidInput)
	}
	libraryID := strings.TrimSpace(in.LibraryID)
	if !checkRunes(libraryID, MaxLibraryIDRunes) {
		return Detail{}, false, fmt.Errorf("%w: libraryId 过长", ErrInvalidInput)
	}
	rulesVersion := strings.TrimSpace(in.RulesVersion)
	if !checkRunes(rulesVersion, MaxRulesVersionRunes) {
		return Detail{}, false, fmt.Errorf("%w: rulesVersion 过长", ErrInvalidInput)
	}
	key := strings.TrimSpace(in.IdempotencyKey)
	if key == "" || !checkRunes(key, MaxIdempotencyKeyRunes) {
		return Detail{}, false, fmt.Errorf("%w: idempotencyKey 须为 1–%d 字符", ErrInvalidInput, MaxIdempotencyKeyRunes)
	}
	payload, err := canonicalJSONObject(in.Payload, MaxPayloadBytes)
	if err != nil {
		return Detail{}, false, err
	}
	budget, err := canonicalJSONObject(in.Budget, MaxBudgetBytes)
	if err != nil {
		return Detail{}, false, err
	}
	fingerprint, err := inputFingerprint(kind, libraryID, payload, rulesVersion, budget)
	if err != nil {
		return Detail{}, false, err
	}

	now := s.now().UTC()
	nowText := store.FormatTimestamp(now)
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Detail{}, false, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	existing, err := q.GetMaintenanceJobByIdempotencyKey(ctx, key)
	if err == nil {
		// 幂等冲突保护（任务书 §8.3）：同一幂等键只对应同一语义请求。
		// 指纹相同才保留既有重放语义（200，created=false）；kind/库/载荷/
		// 规则版本/预算任一不同都说明调用方误用了旧键，必须 409 而不是悄悄
		// 返回另一笔任务的成功响应。
		if existing.InputFingerprint != fingerprint {
			return Detail{}, false, fmt.Errorf("%w: 幂等键已被不同输入使用", ErrStateConflict)
		}
		// Replay: release the write transaction before loading the detail on a
		// separate connection (the store uses _txlock=immediate).
		s.rollback(tx)
		detail, getErr := s.Get(ctx, existing.ID)
		return detail, false, getErr
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Detail{}, false, err
	}
	id := uuid.NewString()
	params := db.CreateMaintenanceJobParams{
		ID:                 id,
		Kind:               string(kind),
		Status:             string(StatusQueued),
		LibraryID:          nullableString(libraryID),
		PayloadJson:        string(payload),
		InputFingerprint:   fingerprint,
		RulesVersion:       rulesVersion,
		AuthorizationScope: "",
		BudgetJson:         string(budget),
		IdempotencyKey:     key,
		LeaseOwner:         sql.NullString{},
		LeaseUntil:         sql.NullString{},
		CreatedAt:          nowText,
		UpdatedAt:          nowText,
		StartedAt:          sql.NullString{},
		FinishedAt:         sql.NullString{},
		ErrorCode:          sql.NullString{},
		ErrorMessage:       sql.NullString{},
	}
	if err := q.CreateMaintenanceJob(ctx, params); err != nil {
		// Concurrent replay can lose the race at the unique index: release the
		// transaction, then return the winner instead of a 500.
		s.rollback(tx)
		winner, getErr := s.q.GetMaintenanceJobByIdempotencyKey(ctx, key)
		if getErr == nil {
			// 并发竞争同样要过指纹比较，不能把不同载荷的请求当成重放。
			if winner.InputFingerprint != fingerprint {
				return Detail{}, false, fmt.Errorf("%w: 幂等键已被不同输入使用", ErrStateConflict)
			}
			detail, detailErr := s.Get(ctx, winner.ID)
			return detail, false, detailErr
		}
		return Detail{}, false, err
	}
	if err := tx.Commit(); err != nil {
		return Detail{}, false, err
	}
	detail, err := s.Get(ctx, id)
	return detail, true, err
}

// List returns a newest-first keyset page. Limit is normalized to
// DefaultListLimit when zero and rejected outside 1..MaxListLimit.
func (s *Service) List(ctx context.Context, opts ListOptions) (ListResult, error) {
	limit := opts.Limit
	if limit == 0 {
		limit = DefaultListLimit
	}
	if limit < 1 || limit > MaxListLimit {
		return ListResult{}, fmt.Errorf("%w: limit 须在 1–%d", ErrInvalidInput, MaxListLimit)
	}
	status := Status(strings.TrimSpace(string(opts.Status)))
	if status != "" && !status.Valid() {
		return ListResult{}, fmt.Errorf("%w: status 不合法", ErrInvalidInput)
	}
	var statusParam any
	if status != "" {
		statusParam = sql.NullString{String: string(status), Valid: true}
	}
	var cursorCreatedAt any
	cursorID := sql.NullString{}
	if strings.TrimSpace(opts.Cursor) != "" {
		cursor, err := decodeCursor(opts.Cursor)
		if err != nil {
			return ListResult{}, err
		}
		cursorCreatedAt = sql.NullString{String: cursor.CreatedAt, Valid: true}
		cursorID = sql.NullString{String: cursor.ID, Valid: true}
	}
	rows, err := s.q.ListMaintenanceJobs(ctx, db.ListMaintenanceJobsParams{
		Status:          statusParam,
		CursorCreatedAt: cursorCreatedAt,
		CursorID:        cursorID,
		RowLimit:        int64(limit) + 1,
	})
	if err != nil {
		return ListResult{}, err
	}
	hasMore := len(rows) > limit
	if hasMore {
		rows = rows[:limit]
	}
	items := make([]Job, 0, len(rows))
	for i := range rows {
		items = append(items, jobFromListRow(rows[i]))
	}
	next := ""
	if hasMore && len(rows) > 0 {
		last := rows[len(rows)-1]
		next = encodeCursor(last.CreatedAt, last.ID)
	}
	return ListResult{Items: items, NextCursor: next}, nil
}

// Get returns a job with up to MaxDetailSteps recent steps and
// MaxDetailArtifacts recent artifacts.
func (s *Service) Get(ctx context.Context, id string) (Detail, error) {
	id, err := normalizeID(id)
	if err != nil {
		return Detail{}, err
	}
	row, err := s.q.GetMaintenanceJob(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return Detail{}, ErrNotFound
	}
	if err != nil {
		return Detail{}, err
	}
	counts, err := s.q.CountMaintenanceStepsByJob(ctx, id)
	if err != nil {
		return Detail{}, err
	}
	stepRows, err := s.q.ListMaintenanceSteps(ctx, db.ListMaintenanceStepsParams{
		JobID: id,
		Limit: MaxDetailSteps,
	})
	if err != nil {
		return Detail{}, err
	}
	artifactRows, err := s.q.ListMaintenanceArtifacts(ctx, db.ListMaintenanceArtifactsParams{
		JobID: id,
		Limit: MaxDetailArtifacts,
	})
	if err != nil {
		return Detail{}, err
	}
	job := jobFromRow(row)
	job.StepCount = int(counts.StepCount)
	job.Progress = progressFor(job.Status, job.StepCount, int(counts.CompletedSteps))
	detail := Detail{
		Job:       job,
		Steps:     make([]Step, 0, len(stepRows)),
		Artifacts: make([]Artifact, 0, len(artifactRows)),
	}
	for i := range stepRows {
		detail.Steps = append(detail.Steps, stepFromRow(stepRows[i]))
	}
	for i := range artifactRows {
		detail.Artifacts = append(detail.Artifacts, artifactFromRow(artifactRows[i]))
	}
	return detail, nil
}

// Pause moves a non-terminal job to paused and clears any lease. Pausing an
// already paused or terminal job is ErrStateConflict.
func (s *Service) Pause(ctx context.Context, id string) (Detail, error) {
	id, err := normalizeID(id)
	if err != nil {
		return Detail{}, err
	}
	now := s.now().UTC()
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Detail{}, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	row, err := getJobTx(ctx, q, id)
	if err != nil {
		return Detail{}, err
	}
	if !Status(row.Status).CanPause() {
		return Detail{}, ErrStateConflict
	}
	affected, err := q.PauseMaintenanceJob(ctx, db.PauseMaintenanceJobParams{
		UpdatedAt: store.FormatTimestamp(now),
		ID:        id,
	})
	if err != nil {
		return Detail{}, err
	}
	if affected == 0 {
		return Detail{}, ErrStateConflict
	}
	if err := tx.Commit(); err != nil {
		return Detail{}, err
	}
	return s.Get(ctx, id)
}

// Resume re-queues a paused job and clears its last error. Resuming anything
// else is ErrStateConflict. The worker restarts the pipeline at inventory.
func (s *Service) Resume(ctx context.Context, id string) (Detail, error) {
	id, err := normalizeID(id)
	if err != nil {
		return Detail{}, err
	}
	now := s.now().UTC()
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Detail{}, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	row, err := getJobTx(ctx, q, id)
	if err != nil {
		return Detail{}, err
	}
	if !Status(row.Status).CanResume() {
		return Detail{}, ErrStateConflict
	}
	affected, err := q.ResumeMaintenanceJob(ctx, db.ResumeMaintenanceJobParams{
		UpdatedAt: store.FormatTimestamp(now),
		ID:        id,
	})
	if err != nil {
		return Detail{}, err
	}
	if affected == 0 {
		return Detail{}, ErrStateConflict
	}
	if err := tx.Commit(); err != nil {
		return Detail{}, err
	}
	return s.Get(ctx, id)
}

// Cancel moves any non-terminal job to cancelled and records finished_at.
func (s *Service) Cancel(ctx context.Context, id string) (Detail, error) {
	id, err := normalizeID(id)
	if err != nil {
		return Detail{}, err
	}
	now := s.now().UTC()
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Detail{}, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	row, err := getJobTx(ctx, q, id)
	if err != nil {
		return Detail{}, err
	}
	if !Status(row.Status).CanCancel() {
		return Detail{}, ErrStateConflict
	}
	affected, err := q.CancelMaintenanceJob(ctx, db.CancelMaintenanceJobParams{
		FinishedAt: sql.NullString{String: store.FormatTimestamp(now), Valid: true},
		UpdatedAt:  store.FormatTimestamp(now),
		ID:         id,
	})
	if err != nil {
		return Detail{}, err
	}
	if affected == 0 {
		return Detail{}, ErrStateConflict
	}
	if err := tx.Commit(); err != nil {
		return Detail{}, err
	}
	return s.Get(ctx, id)
}

// RecordStep upserts one pipeline step (internal worker primitive; no HTTP
// endpoint in this slice).
func (s *Service) RecordStep(ctx context.Context, in StepInput) error {
	jobID, err := normalizeID(in.JobID)
	if err != nil {
		return err
	}
	step := strings.TrimSpace(in.Step)
	if step == "" || !checkRunes(step, MaxStepNameRunes) {
		return fmt.Errorf("%w: step 须为 1–%d 字符", ErrInvalidInput, MaxStepNameRunes)
	}
	if !in.Status.Valid() {
		return fmt.Errorf("%w: step status 不合法", ErrInvalidInput)
	}
	if in.Attempt < 0 {
		return fmt.Errorf("%w: attempt 不能为负", ErrInvalidInput)
	}
	detail, err := canonicalJSONObject(in.Detail, MaxPayloadBytes)
	if err != nil {
		return err
	}
	var startedAt, finishedAt sql.NullString
	if in.StartedAt != nil {
		startedAt = sql.NullString{String: store.FormatTimestamp(*in.StartedAt), Valid: true}
	}
	if in.FinishedAt != nil {
		finishedAt = sql.NullString{String: store.FormatTimestamp(*in.FinishedAt), Valid: true}
	}
	return s.q.UpsertMaintenanceStep(ctx, db.UpsertMaintenanceStepParams{
		JobID:      jobID,
		Step:       step,
		Status:     string(in.Status),
		Attempt:    int64(in.Attempt),
		StartedAt:  startedAt,
		FinishedAt: finishedAt,
		DetailJson: string(detail),
	})
}

// AddArtifact indexes one artifact reference (internal worker primitive; no
// HTTP endpoint in this slice). Repeating the same (job, kind, uri) is a no-op.
func (s *Service) AddArtifact(ctx context.Context, in ArtifactInput) error {
	jobID, err := normalizeID(in.JobID)
	if err != nil {
		return err
	}
	kind := strings.TrimSpace(in.Kind)
	if kind == "" || !checkRunes(kind, MaxArtifactKindRunes) {
		return fmt.Errorf("%w: artifact kind 须为 1–%d 字符", ErrInvalidInput, MaxArtifactKindRunes)
	}
	uri := strings.TrimSpace(in.URI)
	if uri == "" || !checkRunes(uri, MaxArtifactURIRunes) {
		return fmt.Errorf("%w: artifact uri 须为 1–%d 字符", ErrInvalidInput, MaxArtifactURIRunes)
	}
	sha, err := normalizeSHA256(in.SHA256)
	if err != nil {
		return err
	}
	if in.Bytes < 0 {
		return fmt.Errorf("%w: artifact bytes 不能为负", ErrInvalidInput)
	}
	createdAt := in.CreatedAt
	if createdAt.IsZero() {
		createdAt = s.now().UTC()
	}
	return s.q.InsertMaintenanceArtifact(ctx, db.InsertMaintenanceArtifactParams{
		JobID:     jobID,
		Kind:      kind,
		Uri:       uri,
		Sha256:    sha,
		Bytes:     in.Bytes,
		CreatedAt: store.FormatTimestamp(createdAt),
	})
}

func (s *Service) rollback(tx *sql.Tx) {
	if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
		s.logger.Warn("回滚维护任务事务失败", "error", err)
	}
}

func getJobTx(ctx context.Context, q *db.Queries, id string) (db.MaintenanceJob, error) {
	row, err := q.GetMaintenanceJob(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return db.MaintenanceJob{}, ErrNotFound
	}
	return row, err
}

func normalizeID(id string) (string, error) {
	id = strings.TrimSpace(id)
	if id == "" || !checkRunes(id, 100) {
		return "", fmt.Errorf("%w: 任务 ID 不合法", ErrInvalidInput)
	}
	return id, nil
}

func checkRunes(value string, max int) bool {
	return len([]rune(value)) <= max
}

func nullableString(value string) sql.NullString {
	if value == "" {
		return sql.NullString{}
	}
	return sql.NullString{String: value, Valid: true}
}

func parseTimestamp(value string) time.Time {
	t, err := time.Parse(store.TimestampLayout, value)
	if err != nil {
		return time.Time{}
	}
	return t
}

func parseNullTimestamp(value sql.NullString) *time.Time {
	if !value.Valid || value.String == "" {
		return nil
	}
	t := parseTimestamp(value.String)
	if t.IsZero() {
		return nil
	}
	return &t
}

func canonicalJSONObject(raw []byte, maxBytes int) ([]byte, error) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 {
		return []byte("{}"), nil
	}
	if len(trimmed) > maxBytes {
		return nil, fmt.Errorf("%w: JSON 对象超过 %d 字节", ErrInvalidInput, maxBytes)
	}
	decoder := json.NewDecoder(bytes.NewReader(trimmed))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, fmt.Errorf("%w: JSON 对象不合法", ErrInvalidInput)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return nil, fmt.Errorf("%w: JSON 对象后有多余内容", ErrInvalidInput)
	}
	object, ok := value.(map[string]any)
	if !ok {
		return nil, fmt.Errorf("%w: 须为 JSON 对象", ErrInvalidInput)
	}
	out, err := json.Marshal(object)
	if err != nil {
		return nil, fmt.Errorf("%w: JSON 对象不合法", ErrInvalidInput)
	}
	if len(out) > maxBytes {
		return nil, fmt.Errorf("%w: JSON 对象超过 %d 字节", ErrInvalidInput, maxBytes)
	}
	return out, nil
}

func inputFingerprint(kind Kind, libraryID string, payload []byte, rulesVersion string, budget []byte) (string, error) {
	envelope := struct {
		Kind         string          `json:"kind"`
		LibraryID    string          `json:"libraryId,omitempty"`
		Payload      json.RawMessage `json:"payload"`
		RulesVersion string          `json:"rulesVersion,omitempty"`
		Budget       json.RawMessage `json:"budget"`
	}{
		Kind:         string(kind),
		LibraryID:    libraryID,
		Payload:      json.RawMessage(payload),
		RulesVersion: rulesVersion,
		Budget:       json.RawMessage(budget),
	}
	raw, err := json.Marshal(envelope)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:]), nil
}

func normalizeSHA256(value string) (string, error) {
	value = strings.ToLower(strings.TrimSpace(value))
	if len(value) != 64 {
		return "", fmt.Errorf("%w: sha256 须为 64 位十六进制", ErrInvalidInput)
	}
	if _, err := hex.DecodeString(value); err != nil {
		return "", fmt.Errorf("%w: sha256 须为 64 位十六进制", ErrInvalidInput)
	}
	return value, nil
}

type listCursor struct {
	CreatedAt string
	ID        string
}

func encodeCursor(createdAt, id string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(createdAt + "\x00" + id))
}

func decodeCursor(value string) (listCursor, error) {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 512 {
		return listCursor{}, ErrInvalidCursor
	}
	raw, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return listCursor{}, ErrInvalidCursor
	}
	parts := strings.SplitN(string(raw), "\x00", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return listCursor{}, ErrInvalidCursor
	}
	if _, err := time.Parse(store.TimestampLayout, parts[0]); err != nil {
		return listCursor{}, ErrInvalidCursor
	}
	return listCursor{CreatedAt: parts[0], ID: parts[1]}, nil
}

func jobFromRow(row db.MaintenanceJob) Job {
	return Job{
		ID:                 row.ID,
		Kind:               Kind(row.Kind),
		Status:             Status(row.Status),
		LibraryID:          row.LibraryID.String,
		Payload:            json.RawMessage(row.PayloadJson),
		InputFingerprint:   row.InputFingerprint,
		RulesVersion:       row.RulesVersion,
		AuthorizationScope: row.AuthorizationScope,
		Budget:             json.RawMessage(row.BudgetJson),
		IdempotencyKey:     row.IdempotencyKey,
		LeaseOwner:         row.LeaseOwner.String,
		LeaseUntil:         parseNullTimestamp(row.LeaseUntil),
		CreatedAt:          parseTimestamp(row.CreatedAt),
		UpdatedAt:          parseTimestamp(row.UpdatedAt),
		StartedAt:          parseNullTimestamp(row.StartedAt),
		FinishedAt:         parseNullTimestamp(row.FinishedAt),
		ErrorCode:          row.ErrorCode.String,
		ErrorMessage:       row.ErrorMessage.String,
	}
}

func jobFromListRow(row db.ListMaintenanceJobsRow) Job {
	job := Job{
		ID:                 row.ID,
		Kind:               Kind(row.Kind),
		Status:             Status(row.Status),
		LibraryID:          row.LibraryID.String,
		Payload:            json.RawMessage(row.PayloadJson),
		InputFingerprint:   row.InputFingerprint,
		RulesVersion:       row.RulesVersion,
		AuthorizationScope: row.AuthorizationScope,
		Budget:             json.RawMessage(row.BudgetJson),
		IdempotencyKey:     row.IdempotencyKey,
		LeaseOwner:         row.LeaseOwner.String,
		LeaseUntil:         parseNullTimestamp(row.LeaseUntil),
		CreatedAt:          parseTimestamp(row.CreatedAt),
		UpdatedAt:          parseTimestamp(row.UpdatedAt),
		StartedAt:          parseNullTimestamp(row.StartedAt),
		FinishedAt:         parseNullTimestamp(row.FinishedAt),
		ErrorCode:          row.ErrorCode.String,
		ErrorMessage:       row.ErrorMessage.String,
		StepCount:          int(row.StepCount),
	}
	job.Progress = progressFor(job.Status, job.StepCount, int(row.CompletedSteps))
	return job
}

func stepFromRow(row db.MaintenanceStep) Step {
	return Step{
		Step:       row.Step,
		Status:     StepStatus(row.Status),
		Attempt:    int(row.Attempt),
		StartedAt:  parseNullTimestamp(row.StartedAt),
		FinishedAt: parseNullTimestamp(row.FinishedAt),
		Detail:     json.RawMessage(row.DetailJson),
	}
}

func artifactFromRow(row db.MaintenanceArtifact) Artifact {
	return Artifact{
		Kind:      row.Kind,
		URI:       row.Uri,
		SHA256:    row.Sha256,
		Bytes:     row.Bytes,
		CreatedAt: parseTimestamp(row.CreatedAt),
	}
}
