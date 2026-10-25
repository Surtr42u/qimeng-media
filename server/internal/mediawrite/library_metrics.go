package mediawrite

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"qimeng-media/server/internal/safego"
)

const (
	// 监控允许秒级陈旧：固定首个脏信号起一秒合并，不因持续上传滑动延期。
	libraryMetricsMergeInterval = time.Second
	// 沿用原查询三秒预算；不再占用业务成功响应路径。
	libraryMetricsRefreshTimeout = 3 * time.Second
)

// 一个 worker 与一个脏位；在查询期间的变更保留给下一轮，不逐文件创建 goroutine。
type libraryMetricsRefresher struct {
	mu              sync.Mutex
	started, closed bool
	dirty           chan struct{}
	done            chan struct{}
	cancel          context.CancelFunc
	refresh         func(context.Context) error
	logger          *slog.Logger
	wait            func(context.Context) bool
}

func newLibraryMetricsRefresher(refresh func(context.Context) error, logger *slog.Logger) *libraryMetricsRefresher {
	return &libraryMetricsRefresher{dirty: make(chan struct{}, 1), refresh: refresh, logger: logger, wait: waitLibraryMetricsMerge}
}

func waitLibraryMetricsMerge(ctx context.Context) bool {
	timer := time.NewTimer(libraryMetricsMergeInterval)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func (r *libraryMetricsRefresher) notify() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return
	}
	select {
	case r.dirty <- struct{}{}:
	default:
	}
}

func (r *libraryMetricsRefresher) start(parent context.Context) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.started || r.closed {
		return
	}
	ctx, cancel := context.WithCancel(parent)
	r.started = true
	r.cancel = cancel
	r.done = make(chan struct{})
	safego.Go(r.logger, "库文件指标合并刷新", func() { defer close(r.done); r.run(ctx) })
}

func (r *libraryMetricsRefresher) close() {
	r.mu.Lock()
	r.closed = true
	if !r.started {
		r.mu.Unlock()
		return
	}
	r.cancel()
	done := r.done
	r.mu.Unlock()
	<-done
}

func (r *libraryMetricsRefresher) run(ctx context.Context) {
	// 启动立即取一次真相；后续只在脏位上工作，无无变更的常驻 SQL 轮询。
	refresh := func() {
		queryCtx, cancel := context.WithTimeout(ctx, libraryMetricsRefreshTimeout)
		defer cancel()
		if err := r.refresh(queryCtx); err != nil && ctx.Err() == nil {
			r.logger.Warn("刷新 library_files 指标失败，保留上次值", "err", err)
			r.notify() // 失败仍脏，按同一有界节奏再试。
		}
	}
	refresh()
	for {
		select {
		case <-ctx.Done():
			return
		case <-r.dirty:
		}
		if !r.wait(ctx) {
			return
		}
		// 合并本轮等待中的全部变更；此后到达的信号属于下一轮。
		select {
		case <-r.dirty:
		default:
		}
		if ctx.Err() != nil {
			return
		}
		refresh()
	}
}

// StartLibraryMetrics 只允许一个 worker，必须由组合根显式管理生命周期。
func (s *Service) StartLibraryMetrics(ctx context.Context) { s.libraryMetrics.start(ctx) }

// CloseLibraryMetrics 在关数据库前收齐在途查询；停机丢弃尚未刷新的监控脏位。
func (s *Service) CloseLibraryMetrics() { s.libraryMetrics.close() }
