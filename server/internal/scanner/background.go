package scanner

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"golang.org/x/sync/errgroup"

	"qimeng-media/server/internal/safego"
	"qimeng-media/server/internal/store/db"
)

// defaultWatchRefreshInterval 仅查库列表，管理新增/移除/重挂与失败重试；
// 不遍历媒体树。失效监听在冷却到期后的管理周期重新注册。
const defaultWatchRefreshInterval = 30 * time.Second

// defaultWatchRetryInterval 目录数量超 OS 句柄上限时，反复递归注册也很贵；
// 同根失败至多每 5min 重试，根路径变化或新注册库不受冷却限制。
const defaultWatchRetryInterval = 5 * time.Minute

// BackgroundOptions 后台扫描配置；轮询独立于监听，网络盘漏事件仍可补齐。
type BackgroundOptions struct {
	PollInterval time.Duration
	WatchEnabled bool
}

// Start 同时管理轮询和动态库监听，取消后等待所有监听句柄与处理任务退出。
func (s *Scanner) Start(ctx context.Context, options BackgroundOptions) error {
	group, ctx := errgroup.WithContext(ctx)
	group.Go(func() error {
		return safego.Run(s.logger, "扫描轮询", func() error { return s.StartBackground(ctx, options.PollInterval) })
	})
	if options.WatchEnabled {
		group.Go(func() error {
			return safego.Run(s.logger, "扫描监听主管", func() error { return s.watchLibraries(ctx, defaultWatchRefreshInterval) })
		})
	}
	return group.Wait()
}

type activeWatch struct {
	lib     db.Library
	cancel  context.CancelFunc
	done    <-chan struct{}
	retryAt time.Time
}

func (s *Scanner) watchLibraries(ctx context.Context, interval time.Duration) error {
	workers, workerCtx := errgroup.WithContext(ctx)
	active := make(map[string]activeWatch)
	defer func() {
		for _, watch := range active {
			watch.cancel()
		}
		if err := workers.Wait(); err != nil {
			s.logger.Warn("scanner: 等待库监听退出失败", "err", err)
		}
	}()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		libs, err := s.q.ListLibraries(ctx)
		if err != nil && ctx.Err() == nil {
			s.logger.Warn("scanner: 监听列举库失败", "err", err)
		} else if err == nil {
			s.syncWatches(workerCtx, workers, active, libs)
		}
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
}

func (s *Scanner) syncWatches(ctx context.Context, workers *errgroup.Group, active map[string]activeWatch, libs []db.Library) {
	desired := make(map[string]db.Library, len(libs))
	for _, lib := range libs {
		desired[lib.ID] = lib
	}
	for id, watch := range active {
		select {
		case <-watch.done:
			watch.cancel()
			lib, exists := desired[id]
			if exists && lib.RootPath == watch.lib.RootPath && lib.Kind == watch.lib.Kind && time.Now().Before(watch.retryAt) {
				continue
			}
			delete(active, id)
		default:
			lib, exists := desired[id]
			if !exists || lib.RootPath != watch.lib.RootPath || lib.Kind != watch.lib.Kind {
				watch.cancel() // 旧监听完全退出后再注册，避免同库重复处理。
			}
		}
	}
	for _, lib := range libs {
		if _, exists := active[lib.ID]; exists || ctx.Err() != nil {
			continue
		}
		watchCtx, cancel := context.WithCancel(ctx)
		done := make(chan struct{})
		active[lib.ID] = activeWatch{lib: lib, cancel: cancel, done: done, retryAt: time.Now().Add(defaultWatchRetryInterval)}
		workers.Go(func() error {
			defer close(done)
			err := safego.Run(s.logger, "单库监听", func() error { return s.Watch(watchCtx, lib) })
			if err != nil && watchCtx.Err() == nil {
				s.logger.Warn("scanner: 库监听退出，等待重试（轮询兜底）", "libraryId", lib.ID, "err", err)
			}
			return nil // 单库失败不取消其他库和轮询。
		})
	}
}

// StartBackground 只做定时全量轮询，可独立运行于不支持事件监听的存储。
func (s *Scanner) StartBackground(ctx context.Context, interval time.Duration) error {
	if interval <= 0 {
		interval = DefaultPollInterval
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	s.logger.Info("scanner: 轮询兜底已启动", "interval", interval.String())
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			libs, err := s.q.ListLibraries(ctx)
			if err != nil {
				if ctx.Err() == nil {
					s.logger.Warn("scanner: 轮询列举库失败", "err", err)
				}
				continue
			}
			if err := s.pollLibraries(ctx, libs); err != nil {
				return err
			}
		}
	}
}

// pollLibraries对每个子任务独立兜底，单库panic不杀进程，也不取消其他库。
func (s *Scanner) pollLibraries(ctx context.Context, libs []db.Library) error {
	var batch errgroup.Group
	batch.SetLimit(s.scanWorkerLimit(ctx))
	for _, lib := range libs {
		batch.Go(func() error {
			err := safego.Run(s.logger, "单库轮询", func() error {
				return s.scanBackground(ctx, lib, "轮询扫描")
			})
			if err != nil && !errors.Is(err, ErrAlreadyScanning) && ctx.Err() == nil {
				s.logger.Warn("scanner: 轮询扫描库失败", "libraryId", lib.ID, "err", err)
			}
			return nil // 下一轮可重试；不把单库故障扩散到整批。
		})
	}
	return batch.Wait()
}

// scanBackground复用Scan的全局并发闸门，手动/轮询/监听均受同一配置约束。
func (s *Scanner) scanBackground(ctx context.Context, lib db.Library, reason string) error {
	start := time.Now()
	res, err := s.Scan(ctx, lib)
	if err == nil {
		s.logScan(ctx, reason, res, time.Since(start))
	}
	return err
}

func (s *Scanner) logScan(ctx context.Context, reason string, res ScanResult, elapsed time.Duration) {
	level := slog.LevelInfo
	if res.Added+res.Updated+res.Moved+res.Removed == 0 {
		level = slog.LevelDebug // 空扫留调试证据，日常控制台只展示实际变化。
	}
	s.logger.Log(ctx, level, "scanner: "+reason+"完成", "libraryId", res.LibraryID,
		"scanned", res.Scanned, "elapsedMs", elapsed.Milliseconds(),
		"added", res.Added, "updated", res.Updated, "moved", res.Moved, "removed", res.Removed)
}
