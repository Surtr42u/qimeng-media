// Package mediawrite 编排媒体写入的文件、事务、富化、事件和指标。
// 不依赖 HTTP/生成协议类型；参数/响应映射由 httpapi 负责，生产依赖在 main 装配。
package mediawrite

import (
	"context"
	"database/sql"
	"log/slog"
	"time"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/thumbnail"
	"qimeng-media/server/internal/uploadsess"
)

// Options 是生产组合根的显式依赖；测试可注入时钟和富化函数。
type Options struct {
	Conn    *sql.DB
	Queries *db.Queries
	Bus     *events.Bus
	Config  *config.Config
	Thumbs  *thumbnail.Generator
	Logger  *slog.Logger
	Now     func() time.Time
	Enrich  func(context.Context, string, string) error
}

// Service 持有写入流程依赖，不持有请求/协议对象或第二份业务数据。
type Service struct {
	conn    *sql.DB
	q       *db.Queries
	bus     *events.Bus
	cfg     *config.Config
	thumbs  *thumbnail.Generator
	logger  *slog.Logger
	now     func() time.Time
	enrich  func(context.Context, string, string) error
	uploads *uploadsess.Manager
}

func New(opts Options) *Service {
	if opts.Logger == nil {
		opts.Logger = slog.Default()
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	return &Service{conn: opts.Conn, q: opts.Queries, bus: opts.Bus, cfg: opts.Config,
		thumbs: opts.Thumbs, logger: opts.Logger, now: opts.Now, enrich: opts.Enrich,
		uploads: uploadsess.New(opts.Config.DataDir, opts.Now, opts.Logger)}
}

// UploadSessions 供单步会话读写适配和生命周期管理共享同一个注册表。
func (s *Service) UploadSessions() *uploadsess.Manager { return s.uploads }

func (s *Service) enrichAsset(ctx context.Context, libraryID, assetID string) error {
	if s.enrich == nil {
		return nil
	}
	return s.enrich(ctx, libraryID, assetID)
}

// PublishLibraryChanged 广播统一变更信号，客户端刷新及修订号订阅保持原语义。
func (s *Service) PublishLibraryChanged() {
	if err := s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		s.logger.Warn("发布库变更事件失败", "err", err)
	}
}

// libraryMetricsRefreshTimeout 独立于探针超时，限制请求路径上的后台计数刷新。
const libraryMetricsRefreshTimeout = 3 * time.Second

// RefreshLibraryFileMetrics 复用一次分组查询，不在多个写入流程复制指标口径。
func (s *Service) RefreshLibraryFileMetrics() {
	ctx, cancel := context.WithTimeout(context.Background(), libraryMetricsRefreshTimeout)
	defer cancel()
	rows, err := s.q.CountAllLibrariesMedia(ctx)
	if err != nil {
		s.logger.Warn("刷新 library_files 指标失败", "err", err)
		return
	}
	var image, video int64
	for _, row := range rows {
		switch row.MediaType {
		case scanner.MediaTypeImage, scanner.MediaTypeAnimatedImage:
			image += row.Cnt
		case scanner.MediaTypeVideo:
			video += row.Cnt
		}
	}
	sysmon.Default.SetLibraryFiles(sysmon.FileImage, image)
	sysmon.Default.SetLibraryFiles(sysmon.FileVideo, video)
}
