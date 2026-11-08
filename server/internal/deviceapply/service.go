package deviceapply

import (
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
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// Options assembles the device apply service. Conn and Queries are expected to
// be the same store already validated by the composition root; tests pass a
// migrated temporary SQLite database and a temporary root directory.
type Options struct {
	Conn    *sql.DB
	Queries *db.Queries
	// RootDir is the package byte root (<dataDir>/device-apply-packages).
	RootDir string
	Now     func() time.Time
	Logger  *slog.Logger
}

// Service is the device apply ledger plus its package byte store.
type Service struct {
	conn    *sql.DB
	q       *db.Queries
	rootDir string
	now     func() time.Time
	logger  *slog.Logger
}

// New constructs the service. RootDir defaults to a relative
// "device-apply-packages" directory only when the caller omits it; production
// assembly always passes <dataDir>/device-apply-packages.
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
	root := strings.TrimSpace(opts.RootDir)
	if root == "" {
		root = "device-apply-packages"
	}
	return &Service{conn: opts.Conn, q: q, rootDir: filepath.Clean(root), now: now, logger: logger}
}

// Create validates the manifest, writes every segment to disk while hashing
// it, and only after all segments match inserts the task and segment rows in
// one transaction. On any failure the task directory is removed.
func (s *Service) Create(ctx context.Context, in CreateInput) (Task, error) {
	manifest, manifestJSON, err := parseManifest(in.Manifest)
	if err != nil {
		return Task{}, err
	}
	if len(in.Segments) != len(manifest.Segments) {
		return Task{}, fmt.Errorf("%w: 上传的段文件与 manifest 段清单不一致", ErrInvalidInput)
	}
	readers := make(map[SegmentKind]io.Reader, len(in.Segments))
	for kind, reader := range in.Segments {
		if !kind.Valid() {
			return Task{}, fmt.Errorf("%w: 不支持的段类型", ErrInvalidInput)
		}
		if reader == nil {
			return Task{}, fmt.Errorf("%w: 段 %s 缺少文件内容", ErrInvalidInput, kind)
		}
		readers[kind] = reader
	}
	for _, spec := range manifest.Segments {
		if readers[spec.Kind] == nil {
			return Task{}, fmt.Errorf("%w: 缺少段 %s 文件", ErrInvalidInput, spec.Kind)
		}
	}

	now := s.now().UTC()
	id := uuid.NewString()
	dir := filepath.Join(s.rootDir, id)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return Task{}, fmt.Errorf("deviceapply: 创建结果包目录失败: %w", err)
	}
	committed := false
	defer func() {
		if !committed {
			s.removeTaskDir(id, dir)
		}
	}()

	specs := append([]ManifestSegment(nil), manifest.Segments...)
	sort.Slice(specs, func(i, j int) bool { return segmentOrder(specs[i].Kind) < segmentOrder(specs[j].Kind) })
	segmentParams := make([]db.CreateDeviceApplySegmentParams, 0, len(specs))
	for _, spec := range specs {
		written, sum, err := writeSegmentFile(dir, spec, readers[spec.Kind])
		if err != nil {
			return Task{}, err
		}
		segmentParams = append(segmentParams, db.CreateDeviceApplySegmentParams{
			TaskID:  id,
			Kind:    string(spec.Kind),
			Bytes:   written,
			Sha256:  sum,
			RelPath: path.Join(id, segmentFileName(spec.Kind)),
		})
	}

	nowText := store.FormatTimestamp(now)
	expiresAt := now.Add(time.Duration(manifest.TTLSeconds) * time.Second)
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return Task{}, err
	}
	defer s.rollback(tx)
	q := s.q.WithTx(tx)
	if err := q.CreateDeviceApplyTask(ctx, db.CreateDeviceApplyTaskParams{
		ID:               id,
		TargetDeviceID:   manifest.TargetDeviceID,
		Status:           string(StatusPending),
		ProtocolVersion:  int64(manifest.ProtocolVersion),
		RulesFingerprint: manifest.RulesFingerprint,
		ManifestJson:     string(manifestJSON),
		CreatedAt:        nowText,
		UpdatedAt:        nowText,
		ClaimedAt:        sql.NullString{},
		FinishedAt:       sql.NullString{},
		ExpiresAt:        store.FormatTimestamp(expiresAt),
		ResultJson:       sql.NullString{},
	}); err != nil {
		return Task{}, err
	}
	for _, params := range segmentParams {
		if err := q.CreateDeviceApplySegment(ctx, params); err != nil {
			return Task{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return Task{}, err
	}
	committed = true
	return s.Get(ctx, id)
}

// List returns a device-addressed newest-first keyset page after lazily
// expiring due tasks.
func (s *Service) List(ctx context.Context, opts ListOptions) (ListResult, error) {
	target := strings.TrimSpace(opts.TargetDeviceID)
	if !ValidTargetDeviceID(target) {
		return ListResult{}, fmt.Errorf("%w: targetDevice 须为 1–%d 个 ASCII 字符", ErrInvalidInput, MaxTargetDeviceIDRunes)
	}
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
	if _, err := s.expireDue(ctx); err != nil {
		return ListResult{}, err
	}
	rows, err := s.q.ListDeviceApplyTasks(ctx, db.ListDeviceApplyTasksParams{
		TargetDeviceID:  target,
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
	items := make([]Task, 0, len(rows))
	for i := range rows {
		items = append(items, taskFromListRow(rows[i]))
	}
	next := ""
	if hasMore && len(rows) > 0 {
		last := rows[len(rows)-1]
		next = encodeCursor(last.CreatedAt, last.ID)
	}
	return ListResult{Items: items, NextCursor: next}, nil
}

// Get returns one task with its segments and recorded result, after lazily
// expiring it when due.
func (s *Service) Get(ctx context.Context, id string) (Task, error) {
	taskID, err := normalizeID(id)
	if err != nil {
		return Task{}, err
	}
	if _, err := s.expireOne(ctx, taskID, s.now().UTC()); err != nil {
		return Task{}, err
	}
	row, err := s.getRow(ctx, taskID)
	if err != nil {
		return Task{}, err
	}
	task := taskFromRow(row)
	segmentRows, err := s.q.ListDeviceApplySegments(ctx, taskID)
	if err != nil {
		return Task{}, err
	}
	task.Segments = make([]Segment, 0, len(segmentRows))
	for i := range segmentRows {
		task.Segments = append(task.Segments, segmentFromRow(segmentRows[i]))
	}
	task.SegmentCount = len(task.Segments)
	// expectedReadback lives only in manifest_json (segments store kind/bytes/
	// sha256); hydrate it for the detail response instead of duplicating the
	// value in a second column.
	if err := hydrateExpectedReadback(row.ManifestJson, task.Segments); err != nil {
		return Task{}, err
	}
	verificationJSON, verifyErr := s.q.GetDeviceApplyVerification(ctx, taskID)
	switch {
	case verifyErr == nil:
		// 旧结果/损坏行不能让详情整页失败：验证快照是附加证据，解析失败
		// 按未验证处理（结果正文仍可读）。
		if verification, decodeErr := decodeVerification(verificationJSON); decodeErr == nil {
			task.Verification = verification
		}
	case !errors.Is(verifyErr, sql.ErrNoRows):
		s.logger.Warn("读取设备结果验证快照失败（按未验证处理）",
			"taskId", taskID, "error", verifyErr)
	}
	if row.ResultJson.Valid && strings.TrimSpace(row.ResultJson.String) != "" {
		result, err := decodeResult(row.ResultJson.String)
		if err != nil {
			return Task{}, err
		}
		result.ReportedAt = parseTimestamp(nullStringValue(row.FinishedAt))
		task.Result = &result
	}
	return task, nil
}

// Claim moves pending -> claimed. Re-claiming an already claimed, not yet
// expired task is idempotent (200) because this slice has no claim-owner
// identity: the same task row is returned without changing claimedAt.
func (s *Service) Claim(ctx context.Context, id string) (Task, error) {
	taskID, err := normalizeID(id)
	if err != nil {
		return Task{}, err
	}
	now := s.now().UTC()
	if _, err := s.expireOne(ctx, taskID, now); err != nil {
		return Task{}, err
	}
	row, err := s.getRow(ctx, taskID)
	if err != nil {
		return Task{}, err
	}
	switch Status(row.Status) {
	case StatusClaimed:
		return s.Get(ctx, taskID)
	case StatusPending:
	default:
		return Task{}, ErrStateConflict
	}
	nowText := store.FormatTimestamp(now)
	affected, err := s.q.ClaimDeviceApplyTask(ctx, db.ClaimDeviceApplyTaskParams{
		ClaimedAt: sql.NullString{String: nowText, Valid: true},
		UpdatedAt: nowText,
		ID:        taskID,
		ExpiresAt: nowText,
	})
	if err != nil {
		return Task{}, err
	}
	if affected == 0 {
		// Lost a race against claim/cancel/expiry: re-read and only accept the
		// idempotent claimed outcome.
		current, getErr := s.getRow(ctx, taskID)
		if getErr != nil {
			return Task{}, getErr
		}
		if Status(current.Status) == StatusClaimed {
			return s.Get(ctx, taskID)
		}
		return Task{}, ErrStateConflict
	}
	return s.Get(ctx, taskID)
}

// Result records the phone-reported terminal outcome. Submitting the exact
// same canonical result again is idempotent and returns the stored task;
// submitting a different result for an already finished task, or reporting
// before claim, is ErrStateConflict.
func (s *Service) Result(ctx context.Context, id string, in ResultInput) (Task, error) {
	taskID, err := normalizeID(id)
	if err != nil {
		return Task{}, err
	}
	status, canonical, err := canonicalResult(in)
	if err != nil {
		return Task{}, err
	}
	now := s.now().UTC()
	if _, err := s.expireOne(ctx, taskID, now); err != nil {
		return Task{}, err
	}
	row, err := s.getRow(ctx, taskID)
	if err != nil {
		return Task{}, err
	}
	if Status(row.Status).IsResult() {
		if row.ResultJson.Valid && row.ResultJson.String == string(canonical) {
			return s.Get(ctx, taskID)
		}
		return Task{}, ErrStateConflict
	}
	if Status(row.Status) != StatusClaimed {
		return Task{}, ErrStateConflict
	}
	// 权威状态快照与摘要核验在服务端计算，与客户端上报的增量计数分开存放：
	// result_json 保持客户端规范化内容（幂等重放逐字节比较），验证结果写入
	// device_apply_verifications 独立表，重放不会因库状态变化被误判为
	// STATE_CONFLICT。
	verification, verifyErr := s.buildVerification(ctx, taskID, row.ManifestJson, in.DeviceLocalHashes)
	if verifyErr != nil {
		// 验证快照是附加证据，不是结果写入的前置条件：读取失败按协议留空
		// （authoritativeState=null），终态必须照常落账，不能因一次临时
		// DB 错误让手机的 applied/partial/failed 丢失并最终过期。
		s.logger.Warn("设备结果权威校验失败（结果照常记录，验证字段留空）",
			"taskId", taskID, "error", verifyErr)
		verification = nil
	}
	verificationJSON := ""
	if len(verification) > 0 {
		verificationJSON = string(verification)
	}
	nowText := store.FormatTimestamp(now)
	affected, err := s.q.FinishDeviceApplyTask(ctx, db.FinishDeviceApplyTaskParams{
		Status:     string(status),
		ResultJson: sql.NullString{String: string(canonical), Valid: true},
		FinishedAt: sql.NullString{String: nowText, Valid: true},
		UpdatedAt:  nowText,
		ID:         taskID,
	})
	if err != nil {
		return Task{}, err
	}
	if affected == 0 {
		current, getErr := s.getRow(ctx, taskID)
		if getErr != nil {
			return Task{}, getErr
		}
		if Status(current.Status).IsResult() && current.ResultJson.Valid &&
			current.ResultJson.String == string(canonical) {
			return s.Get(ctx, taskID)
		}
		return Task{}, ErrStateConflict
	}
	// 验证快照独立表、best-effort：写入失败只丢证据，不影响已提交的终态。
	// 独立表而非新列是为了旧版本回退兼容（旧代码对任务表 SELECT * 定长扫描，
	// 新增列会让回退后的设备端点扫描失败）。
	if verificationJSON != "" {
		if insertErr := s.q.InsertDeviceApplyVerification(ctx, db.InsertDeviceApplyVerificationParams{
			TaskID:           taskID,
			VerificationJson: verificationJSON,
			CreatedAt:        nowText,
		}); insertErr != nil {
			s.logger.Warn("写入设备结果验证快照失败（结果已记录）",
				"taskId", taskID, "error", insertErr)
		}
	}
	return s.Get(ctx, taskID)
}

// Cancel moves pending/claimed -> cancelled. Cancelling an already cancelled
// task is idempotent; any other terminal state is ErrStateConflict.
func (s *Service) Cancel(ctx context.Context, id string) (Task, error) {
	taskID, err := normalizeID(id)
	if err != nil {
		return Task{}, err
	}
	now := s.now().UTC()
	if _, err := s.expireOne(ctx, taskID, now); err != nil {
		return Task{}, err
	}
	row, err := s.getRow(ctx, taskID)
	if err != nil {
		return Task{}, err
	}
	if Status(row.Status) == StatusCancelled {
		return s.Get(ctx, taskID)
	}
	if !Status(row.Status).Active() {
		return Task{}, ErrStateConflict
	}
	nowText := store.FormatTimestamp(now)
	affected, err := s.q.CancelDeviceApplyTask(ctx, db.CancelDeviceApplyTaskParams{
		FinishedAt: sql.NullString{String: nowText, Valid: true},
		UpdatedAt:  nowText,
		ID:         taskID,
	})
	if err != nil {
		return Task{}, err
	}
	if affected == 0 {
		current, getErr := s.getRow(ctx, taskID)
		if getErr != nil {
			return Task{}, getErr
		}
		if Status(current.Status) == StatusCancelled {
			return s.Get(ctx, taskID)
		}
		return Task{}, ErrStateConflict
	}
	return s.Get(ctx, taskID)
}

// OpenSegment opens one segment file for streaming. Only active
// (pending/claimed) tasks can be downloaded; terminal or expired tasks return
// ErrStateConflict. The caller owns closing the returned reader.
func (s *Service) OpenSegment(ctx context.Context, id string, kind SegmentKind) (Segment, io.ReadCloser, error) {
	taskID, err := normalizeID(id)
	if err != nil {
		return Segment{}, nil, err
	}
	if !kind.Valid() {
		return Segment{}, nil, fmt.Errorf("%w: 不支持的段类型", ErrInvalidInput)
	}
	if _, err := s.expireOne(ctx, taskID, s.now().UTC()); err != nil {
		return Segment{}, nil, err
	}
	row, err := s.getRow(ctx, taskID)
	if err != nil {
		return Segment{}, nil, err
	}
	if !Status(row.Status).Active() {
		return Segment{}, nil, ErrStateConflict
	}
	segmentRow, err := s.q.GetDeviceApplySegment(ctx, db.GetDeviceApplySegmentParams{
		TaskID: taskID,
		Kind:   string(kind),
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Segment{}, nil, ErrSegmentNotFound
	}
	if err != nil {
		return Segment{}, nil, err
	}
	fullPath, err := s.segmentPath(taskID, kind, segmentRow.RelPath)
	if err != nil {
		return Segment{}, nil, err
	}
	file, err := os.Open(fullPath) // #nosec G304 -- path is server-generated and re-validated against the root.
	if err != nil {
		return Segment{}, nil, fmt.Errorf("deviceapply: 打开段文件失败: %w", err)
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return Segment{}, nil, fmt.Errorf("deviceapply: 读取段文件信息失败: %w", err)
	}
	if !info.Mode().IsRegular() || info.Size() != segmentRow.Bytes {
		_ = file.Close()
		return Segment{}, nil, errors.New("deviceapply: 段文件大小或类型与账本不符")
	}
	return segmentFromRow(segmentRow), file, nil
}

func writeSegmentFile(dir string, spec ManifestSegment, reader io.Reader) (int64, string, error) {
	filePath := filepath.Join(dir, segmentFileName(spec.Kind))
	file, err := os.OpenFile(filePath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return 0, "", fmt.Errorf("deviceapply: 创建段文件失败: %w", err)
	}
	hasher := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(file, hasher), io.LimitReader(reader, spec.Bytes+1))
	syncErr := file.Sync()
	closeErr := file.Close()
	if copyErr != nil {
		return 0, "", fmt.Errorf("deviceapply: 写入段 %s 失败: %w", spec.Kind, copyErr)
	}
	if syncErr != nil {
		return 0, "", fmt.Errorf("deviceapply: 刷新段 %s 失败: %w", spec.Kind, syncErr)
	}
	if closeErr != nil {
		return 0, "", fmt.Errorf("deviceapply: 关闭段 %s 失败: %w", spec.Kind, closeErr)
	}
	if written != spec.Bytes {
		return 0, "", fmt.Errorf("%w: 段 %s 实际字节与清单声明不一致（声明 %d）", ErrInvalidInput, spec.Kind, spec.Bytes)
	}
	sum := hex.EncodeToString(hasher.Sum(nil))
	if sum != spec.SHA256 {
		return 0, "", fmt.Errorf("%w: 段 %s SHA-256 与清单不符", ErrInvalidInput, spec.Kind)
	}
	return written, sum, nil
}

func hydrateExpectedReadback(manifestJSON string, segments []Segment) error {
	if strings.TrimSpace(manifestJSON) == "" {
		return nil
	}
	var manifest Manifest
	if err := json.Unmarshal([]byte(manifestJSON), &manifest); err != nil {
		return fmt.Errorf("deviceapply: manifest 记录不合法: %w", err)
	}
	byKind := make(map[SegmentKind]json.RawMessage, len(manifest.Segments))
	for _, spec := range manifest.Segments {
		byKind[spec.Kind] = spec.ExpectedReadback
	}
	for i := range segments {
		segments[i].ExpectedReadback = byKind[segments[i].Kind]
	}
	return nil
}

func (s *Service) segmentPath(taskID string, kind SegmentKind, relPath string) (string, error) {
	expected := path.Join(taskID, segmentFileName(kind))
	if relPath != expected {
		return "", errors.New("deviceapply: 段路径与账本不一致")
	}
	root, err := filepath.Abs(s.rootDir)
	if err != nil {
		return "", fmt.Errorf("deviceapply: 解析结果包根目录失败: %w", err)
	}
	full, err := filepath.Abs(filepath.Join(s.rootDir, filepath.FromSlash(expected)))
	if err != nil {
		return "", fmt.Errorf("deviceapply: 解析段路径失败: %w", err)
	}
	relative, err := filepath.Rel(root, full)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
		return "", errors.New("deviceapply: 段路径越出结果包根目录")
	}
	return full, nil
}

func (s *Service) expireOne(ctx context.Context, id string, now time.Time) (int64, error) {
	nowText := store.FormatTimestamp(now)
	return s.q.ExpireDeviceApplyTask(ctx, db.ExpireDeviceApplyTaskParams{
		FinishedAt: sql.NullString{String: nowText, Valid: true},
		UpdatedAt:  nowText,
		ID:         id,
		ExpiresAt:  nowText,
	})
}

func (s *Service) expireDue(ctx context.Context) (int64, error) {
	nowText := store.FormatTimestamp(s.now().UTC())
	return s.q.ExpireDeviceApplyTasks(ctx, db.ExpireDeviceApplyTasksParams{
		FinishedAt: sql.NullString{String: nowText, Valid: true},
		UpdatedAt:  nowText,
		ExpiresAt:  nowText,
	})
}

func (s *Service) getRow(ctx context.Context, id string) (db.DeviceApplyTask, error) {
	row, err := s.q.GetDeviceApplyTask(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return db.DeviceApplyTask{}, ErrNotFound
	}
	return row, err
}

func (s *Service) rollback(tx *sql.Tx) {
	if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
		s.logger.Warn("回滚设备结果包事务失败", "error", err)
	}
}

func (s *Service) removeTaskDir(id, dir string) {
	if err := os.RemoveAll(dir); err != nil {
		s.logger.Warn("清理设备结果包目录失败", "taskId", id, "error", err)
	}
}

func segmentFileName(kind SegmentKind) string {
	switch kind {
	case SegmentMasterTxt:
		return "masterTxt.bin"
	case SegmentV2Backup:
		return "v2Backup.bin"
	default:
		return "segment.bin"
	}
}

func segmentOrder(kind SegmentKind) int {
	switch kind {
	case SegmentMasterTxt:
		return 0
	case SegmentV2Backup:
		return 1
	default:
		return 2
	}
}

func normalizeID(value string) (string, error) {
	id, err := uuid.Parse(strings.TrimSpace(value))
	if err != nil {
		return "", fmt.Errorf("%w: 任务 ID 不合法", ErrInvalidInput)
	}
	return id.String(), nil
}

func nullStringValue(value sql.NullString) string {
	if !value.Valid {
		return ""
	}
	return value.String
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

func taskFromRow(row db.DeviceApplyTask) Task {
	task := Task{
		ID:               row.ID,
		TargetDeviceID:   row.TargetDeviceID,
		Status:           Status(row.Status),
		ProtocolVersion:  int(row.ProtocolVersion),
		RulesFingerprint: row.RulesFingerprint,
		CreatedAt:        parseTimestamp(row.CreatedAt),
		UpdatedAt:        parseTimestamp(row.UpdatedAt),
		ClaimedAt:        parseNullTimestamp(row.ClaimedAt),
		FinishedAt:       parseNullTimestamp(row.FinishedAt),
		ExpiresAt:        parseTimestamp(row.ExpiresAt),
	}
	return task
}

func taskFromListRow(row db.ListDeviceApplyTasksRow) Task {
	return Task{
		ID:               row.ID,
		TargetDeviceID:   row.TargetDeviceID,
		Status:           Status(row.Status),
		ProtocolVersion:  int(row.ProtocolVersion),
		RulesFingerprint: row.RulesFingerprint,
		CreatedAt:        parseTimestamp(row.CreatedAt),
		UpdatedAt:        parseTimestamp(row.UpdatedAt),
		ClaimedAt:        parseNullTimestamp(row.ClaimedAt),
		FinishedAt:       parseNullTimestamp(row.FinishedAt),
		ExpiresAt:        parseTimestamp(row.ExpiresAt),
		SegmentCount:     int(row.SegmentCount),
	}
}

func segmentFromRow(row db.DeviceApplySegment) Segment {
	return Segment{
		Kind:    SegmentKind(row.Kind),
		Bytes:   row.Bytes,
		SHA256:  row.Sha256,
		RelPath: row.RelPath,
	}
}

// verificationRecord 是 verification_json 的线上结构（与 OpenAPI
// DeviceApplyResult 的验证字段同名）。
type verificationRecord struct {
	AuthoritativeState   map[string]int64     `json:"authoritativeState"`
	ExpectedStateMatches *bool                `json:"expectedStateMatches,omitempty"`
	ExpectedStateDiff    map[string]StateDiff `json:"expectedStateDiff,omitempty"`
	HashVerified         *bool                `json:"hashVerified,omitempty"`
}

// buildVerification reads the authoritative business-state counts from the
// same database and compares them with the manifest's expectedState (when
// declared) and the phone's reported segment hashes.
func (s *Service) buildVerification(ctx context.Context, taskID, manifestJSON string, hashes []LocalHash) ([]byte, error) {
	stateRow, err := s.q.GetDeviceApplyAuthoritativeState(ctx)
	if err != nil {
		return nil, fmt.Errorf("deviceapply: 读取权威业务状态: %w", err)
	}
	state := map[string]int64{
		"assets":          stateRow.Assets,
		"authors":         stateRow.Authors,
		"assetAuthorRefs": stateRow.AssetAuthorRefs,
		"tags":            stateRow.Tags,
		"assetTagRefs":    stateRow.AssetTagRefs,
		"viewEvents":      stateRow.ViewEvents,
	}
	var manifest struct {
		ExpectedState map[string]int64 `json:"expectedState"`
	}
	if strings.TrimSpace(manifestJSON) != "" {
		if err := json.Unmarshal([]byte(manifestJSON), &manifest); err != nil {
			return nil, fmt.Errorf("deviceapply: manifest 解析失败: %w", err)
		}
	}
	record := verificationRecord{AuthoritativeState: state}
	if len(manifest.ExpectedState) > 0 {
		matches := true
		diff := map[string]StateDiff{}
		for key, expected := range manifest.ExpectedState {
			actual := state[key]
			if actual != expected {
				matches = false
				diff[key] = StateDiff{Expected: expected, Actual: actual}
			}
		}
		record.ExpectedStateMatches = &matches
		if len(diff) > 0 {
			record.ExpectedStateDiff = diff
		}
	}
	if len(hashes) > 0 {
		segmentRows, err := s.q.ListDeviceApplySegments(ctx, taskID)
		if err != nil {
			return nil, fmt.Errorf("deviceapply: 读取段清单: %w", err)
		}
		registered := make(map[SegmentKind]string, len(segmentRows))
		for i := range segmentRows {
			registered[SegmentKind(segmentRows[i].Kind)] = segmentRows[i].Sha256
		}
		verified := len(hashes) == len(registered)
		for _, hash := range hashes {
			sha, ok := registered[hash.Kind]
			if !ok || sha != hash.SHA256 {
				verified = false
			}
		}
		record.HashVerified = &verified
	}
	encoded, err := json.Marshal(record)
	if err != nil {
		return nil, fmt.Errorf("deviceapply: 验证快照序列化失败: %w", err)
	}
	return encoded, nil
}

func decodeVerification(raw string) (*Verification, error) {
	var record verificationRecord
	if err := json.Unmarshal([]byte(raw), &record); err != nil {
		return nil, err
	}
	if len(record.AuthoritativeState) == 0 {
		return nil, errors.New("deviceapply: 验证快照缺少权威状态")
	}
	return &Verification{
		AuthoritativeState:   record.AuthoritativeState,
		ExpectedStateMatches: record.ExpectedStateMatches,
		ExpectedStateDiff:    record.ExpectedStateDiff,
		HashVerified:         record.HashVerified,
	}, nil
}

type resultRecord struct {
	Status            Status          `json:"status"`
	Readback          json.RawMessage `json:"readback"`
	DeviceLocalHashes []LocalHash     `json:"deviceLocalHashes"`
	Message           string          `json:"message,omitempty"`
}

func canonicalResult(in ResultInput) (Status, []byte, error) {
	status := Status(strings.TrimSpace(string(in.Status)))
	if !status.IsResult() {
		return "", nil, fmt.Errorf("%w: result.status 须为 applied/partial/failed", ErrInvalidInput)
	}
	readback, err := canonicalJSONObject(in.Readback, MaxReadbackBytes)
	if err != nil {
		return "", nil, err
	}
	if len(in.DeviceLocalHashes) > MaxDeviceLocalHashes {
		return "", nil, fmt.Errorf("%w: deviceLocalHashes 最多 %d 项", ErrInvalidInput, MaxDeviceLocalHashes)
	}
	hashes := make([]LocalHash, 0, len(in.DeviceLocalHashes))
	seen := make(map[SegmentKind]struct{}, len(in.DeviceLocalHashes))
	for _, item := range in.DeviceLocalHashes {
		kind := SegmentKind(strings.TrimSpace(string(item.Kind)))
		if !kind.Valid() {
			return "", nil, fmt.Errorf("%w: deviceLocalHashes.kind 不合法", ErrInvalidInput)
		}
		if _, ok := seen[kind]; ok {
			return "", nil, fmt.Errorf("%w: deviceLocalHashes 段类型重复", ErrInvalidInput)
		}
		seen[kind] = struct{}{}
		sha, err := normalizeSHA256(item.SHA256)
		if err != nil {
			return "", nil, fmt.Errorf("%w: deviceLocalHashes.sha256 须为 64 位小写十六进制", ErrInvalidInput)
		}
		hashes = append(hashes, LocalHash{Kind: kind, SHA256: sha})
	}
	sort.Slice(hashes, func(i, j int) bool { return hashes[i].Kind < hashes[j].Kind })
	message := strings.TrimSpace(in.Message)
	if len([]rune(message)) > MaxResultMessageRunes {
		return "", nil, fmt.Errorf("%w: result.message 超过 %d 字符", ErrInvalidInput, MaxResultMessageRunes)
	}
	encoded, err := json.Marshal(resultRecord{
		Status:            status,
		Readback:          readback,
		DeviceLocalHashes: hashes,
		Message:           message,
	})
	if err != nil {
		return "", nil, fmt.Errorf("%w: result 无法序列化", ErrInvalidInput)
	}
	return status, encoded, nil
}

func decodeResult(raw string) (Result, error) {
	var record resultRecord
	if err := json.Unmarshal([]byte(raw), &record); err != nil {
		return Result{}, fmt.Errorf("deviceapply: 结果记录不合法: %w", err)
	}
	if !record.Status.IsResult() {
		return Result{}, errors.New("deviceapply: 结果记录状态不合法")
	}
	hashes := record.DeviceLocalHashes
	if hashes == nil {
		hashes = []LocalHash{}
	}
	return Result{
		Status:            record.Status,
		Readback:          record.Readback,
		DeviceLocalHashes: hashes,
		Message:           record.Message,
	}, nil
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
	if _, err := uuid.Parse(parts[1]); err != nil {
		return listCursor{}, ErrInvalidCursor
	}
	return listCursor{CreatedAt: parts[0], ID: parts[1]}, nil
}
