package portablebackup

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authorimport"
	"qimeng-media/server/internal/store/db"
)

// RecommendPrefsKey 与偏好读写端共用；格式仍以 OpenAPI RecommendPrefs 为准。
const RecommendPrefsKey = "recommend_prefs"

// 事件词表与 OpenAPI ViewEventReport.kind 同步，由边界契约测试锁定。
const (
	eventOpen  = "open"
	eventPlay  = "play"
	eventDwell = "dwell"
)

var ErrInvalidBackup = errors.New("无效备份")

type invalidBackup struct{ cause error }

func (e *invalidBackup) Error() string { return e.cause.Error() }
func (e *invalidBackup) Unwrap() error { return ErrInvalidBackup }

// StageError 保留原阶段文案供 HTTP 映射，服务不写响应。
type StageError struct {
	Stage string
	Err   error
}

func (e *StageError) Error() string { return fmt.Sprintf("%s: %v", e.Stage, e.Err) }
func (e *StageError) Unwrap() error { return e.Err }

type Options struct {
	Conn    *sql.DB
	Queries *db.Queries
	TXT     *authorimport.Service
	Mirror  *authorattach.MirrorWriter
	Now     func() time.Time
	Logger  *slog.Logger
	Changed func()
}

type Service struct {
	conn    *sql.DB
	q       *db.Queries
	txt     *authorimport.Service
	mirror  *authorattach.MirrorWriter
	now     func() time.Time
	logger  *slog.Logger
	changed func()
}

func New(opts Options) *Service {
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.Logger == nil {
		opts.Logger = slog.Default()
	}
	if opts.Mirror == nil {
		opts.Mirror = &authorattach.MirrorWriter{Logger: opts.Logger}
	}
	if opts.TXT == nil {
		opts.TXT = authorimport.New(opts.Conn, opts.Queries, opts.Now, opts.Logger)
	}
	return &Service{conn: opts.Conn, q: opts.Queries, txt: opts.TXT, mirror: opts.Mirror, now: opts.Now, logger: opts.Logger, changed: opts.Changed}
}

// Import 先完成 v2 全载荷校验，再依次执行原有分段事务。
// 失败保留既有部分提交，返回首个阶段错误；任何已提交段均通知读侧失效。
func (s *Service) Import(ctx context.Context, req *BackupImport) (ImportResult, error) {
	if req.Format != Format {
		return ImportResult{}, &invalidBackup{cause: errors.New("format 必须为 qimeng_backup")}
	}
	if err := validatePortableBackup(req); err != nil {
		return ImportResult{}, &invalidBackup{cause: err}
	}
	imp := &legacyImport{s: s, ctx: ctx}
	imp.matchBackupFiles(req.Data.MediaFiles, req.SchemaVersion == SchemaVersion)
	imp.importTxtFragments(req.Data.TxtFragments)
	imp.importAuthors(req.Data.Authors, req.Data.AuthorMediaRefs)
	imp.importTags(req.Data.Tags, req.Data.MediaTagRefs)
	imp.replayEvents(req)
	imp.importLikesFavorites(req.Data.Likes, req.Data.Favorites)
	imp.importTimelineTags(req.Data.TimelineTags)
	imp.markFollowed(req.Data.FollowedAuthorIds)
	imp.importPrefs(req.Data.AppPrefs)
	imp.staticWarnings(req.Data)
	for _, warn := range imp.warnings {
		s.logger.Warn("旧版迁移提示", "warning", warn)
	}
	if imp.err == nil || imp.committed {
		if s.changed != nil {
			s.changed()
		}
	}
	if imp.err != nil {
		return imp.res, imp.err
	}
	imp.res.Warnings = &imp.warnings
	return imp.res, nil
}

func (s *Service) Export(ctx context.Context) (*BackupFile, error) {
	return (&legacyExport{s: s, ctx: ctx}).build()
}

func (s *Service) rollbackTx(ctx context.Context, stage string, tx *sql.Tx) {
	if err := tx.Rollback(); err != nil && !errors.Is(err, sql.ErrTxDone) {
		s.logger.WarnContext(ctx, stage+": 事务回滚失败", "error", err)
	}
}

func ptr[T any](v T) *T { return &v }
