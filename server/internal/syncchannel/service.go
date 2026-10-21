// Package syncchannel 编排本机同步的轮询、运行态、媒体搬运与 TXT 导入。
// 匹配/扫描规则复用 localsync；资产写入复用 mediawrite，作者事务复用 authorimport。
// 不依赖 HTTP 或生成协议；生产依赖在 main 显式装配。
package syncchannel

import (
	"errors"
	"log/slog"
	"sync"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authorimport"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/mediawrite"
	"qimeng-media/server/internal/store/db"
)

const dirPerm = 0o755 // 库与同步归档目录权限，同既有服务端目录约定。

type Options struct {
	Config   *config.Config
	Queries  *db.Queries
	Writes   *mediawrite.Service
	TXT      *authorimport.Service
	Mirror   *authorattach.MirrorWriter
	Logger   *slog.Logger
	Now      func() time.Time
	MoveFile func(string, string) error
}

type Service struct {
	cfg       *config.Config
	q         *db.Queries
	writes    *mediawrite.Service
	txt       *authorimport.Service
	mirror    *authorattach.MirrorWriter
	logger    *slog.Logger
	now       func() time.Time
	moveFile  func(string, string) error
	localSync *localSyncState
	cycleMu   sync.Mutex // 手动执行与轮询也串行，防同一源被并发搬运。
}

func New(opts Options) *Service {
	if opts.Logger == nil {
		opts.Logger = slog.Default()
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.MoveFile == nil {
		opts.MoveFile = filing.MoveFile
	}
	if opts.Mirror == nil {
		opts.Mirror = &authorattach.MirrorWriter{Logger: opts.Logger}
	}
	return &Service{cfg: opts.Config, q: opts.Queries, writes: opts.Writes, txt: opts.TXT,
		mirror: opts.Mirror, logger: opts.Logger, now: opts.Now, moveFile: opts.MoveFile,
		localSync: newLocalSyncState()}
}

var ErrDisabled = errors.New("local sync disabled")
var ErrUnavailable = errors.New("local sync unavailable")

// Trigger 非阻塞合并重复触发，不向协议层暴露含本机路径的内部错误。
func (s *Service) Trigger() error {
	if s.cfg.LocalSync.Root == "" {
		return ErrDisabled
	}
	if lastErr := s.lsLastErr(); lastErr != "" {
		s.logger.Warn("本机同步通道安全校验未通过，拒绝触发", "reason", lastErr)
		return ErrUnavailable
	}
	select {
	case s.localSync.kick <- struct{}{}:
	default:
	}
	return nil
}
