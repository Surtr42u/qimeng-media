// Package thumbnailwarmup 编排有界回填及覆盖统计；HTTP 仅负责生命周期与响应映射。
// 复用扫描元数据和真实缓存文件，定时对账修复外部缓存删除，无持久化旁表。
package thumbnailwarmup

import (
	"context"
	"errors"
	"log/slog"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

const (
	// 候选与缺图任务只保留一页，历史回填仍覆盖全部启用库。
	pageSize = 256
	// 保留外部删除缓存后的兜底期限；投递由队列背压与生成侧优先准入限流。
	reconcileInterval = 10 * time.Minute
	// 连续上传/扫描通知不能把整库缓存检查变成高频轮询。
	changeInterval = 30 * time.Second
	retryPause     = 250 * time.Millisecond
	progressTTL    = 30 * time.Second
)

type Candidates interface {
	ListThumbnailWarmupPage(context.Context, db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error)
}

type Thumbnails interface {
	HasStoredThumbnail(string, thumbnail.Size, int64, string) bool
	Submit(thumbnail.Task) error
}

type Snapshot struct {
	Covered int
	Total   int
	at      time.Time
	epoch   uint64
}

type Service struct {
	q         Candidates
	thumbs    Thumbnails
	logger    *slog.Logger
	readyAt   time.Time
	wake      chan struct{}
	pendingMu sync.Mutex
	pending   changeScope
	epoch     atomic.Uint64
	// 回填和统计各自有界，慢速投递期间不锁住进度请求。
	progressGate chan struct{}
	cacheMu      sync.Mutex
	cache        *Snapshot
}

func New(q Candidates, thumbs Thumbnails, logger *slog.Logger, readyAt time.Time) *Service {
	if logger == nil {
		logger = slog.Default()
	}
	return &Service{q: q, thumbs: thumbs, logger: logger, readyAt: readyAt, wake: make(chan struct{}, 1), progressGate: make(chan struct{}, 1)}
}

// Notify 合并突发变更；当前回填期间的通知保留为下一轮，补游标前的新行。
func (s *Service) Notify() { s.notifyScope(changeScope{all: true}) }

// Run 由组合根仅启动一次，等待、SQL 与队列背压均随停机取消。
func (s *Service) Run(ctx context.Context) {
	s.run(ctx, changeInterval)
}

func (s *Service) run(ctx context.Context, changeDelay time.Duration) {
	if !wait(ctx, time.Until(s.readyAt)) {
		return
	}
	s.takePending()
	s.reconcile(ctx)
	// 独立全量兜底时钟；局部变更不能不断延期十分钟的外删缓存修复。
	timer := time.NewTimer(reconcileInterval)
	defer timer.Stop()
	s.runLoop(ctx, changeDelay, timer.C, func() { timer.Reset(reconcileInterval) })
}

func (s *Service) runLoop(ctx context.Context, changeDelay time.Duration, fullTick <-chan time.Time, resetFull func()) {
	lastStart := time.Now()
	for {
		full := false
		select {
		case <-ctx.Done():
			return
		case <-s.wake:
		case <-fullTick:
			full = true
		}
		if !wait(ctx, time.Until(lastStart.Add(changeDelay))) {
			return
		}
		scope := s.takePending()
		if full {
			scope = changeScope{all: true}
		}
		lastStart = time.Now()
		s.reconcileScope(ctx, scope)
		if full {
			resetFull()
		}
	}
}

func (s *Service) drainWake() {
	select {
	case <-s.wake:
	default:
	}
}

func (s *Service) reconcile(ctx context.Context) { s.reconcileScope(ctx, changeScope{all: true}) }

func (s *Service) reconcileScope(ctx context.Context, scope changeScope) {
	if _, ok := s.q.(ScopedCandidates); !ok {
		scope = changeScope{all: true}
	}
	start := time.Now()
	candidates, submitted := 0, 0
	for _, work := range scope.work() {
		snap, n, err := s.inspectScope(ctx, true, work)
		if err != nil {
			if ctx.Err() == nil && !errors.Is(err, thumbnail.ErrPoolClosed) {
				s.logger.Warn("缩略图回填失败（保留范围待办）", "err", err)
				s.retryScope(scope)
			}
			return
		}
		candidates += snap.Total
		submitted += n
		if scope.all {
			s.publish(snap)
		} // 局部数不能冒充全库覆盖快照。
	}
	if submitted > 0 {
		s.logger.Info("缩略图预热投递完成", "candidates", candidates, "submitted", submitted, "full", scope.all, "elapsedMs", time.Since(start).Milliseconds())
	}
}

func (s *Service) Progress(ctx context.Context) (Snapshot, error) {
	if err := ctx.Err(); err != nil {
		return Snapshot{}, err
	}
	if snap, ok := s.fresh(); ok {
		return snap, nil
	}
	select {
	case s.progressGate <- struct{}{}:
		defer func() { <-s.progressGate }()
	case <-ctx.Done():
		return Snapshot{}, ctx.Err()
	}
	if err := ctx.Err(); err != nil {
		return Snapshot{}, err
	}
	if snap, ok := s.fresh(); ok {
		return snap, nil
	}
	snap, _, err := s.inspect(ctx, false)
	if err == nil {
		s.publish(snap)
	}
	return snap, err
}

func (s *Service) fresh() (Snapshot, bool) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	if s.cache == nil || s.cache.epoch != s.epoch.Load() || time.Since(s.cache.at) >= progressTTL {
		return Snapshot{}, false
	}
	return *s.cache, true
}

func (s *Service) publish(snap Snapshot) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()
	// 较慢的回填不能覆盖较新的进度；统计年龄从开始取数算起。
	if snap.epoch == s.epoch.Load() && (s.cache == nil || snap.at.After(s.cache.at)) {
		s.cache = &snap
	}
}

func (s *Service) inspect(ctx context.Context, submit bool) (Snapshot, int, error) {
	return s.inspectScope(ctx, submit, workScope{})
}

func (s *Service) inspectScope(ctx context.Context, submit bool, scope workScope) (Snapshot, int, error) {
	snap := Snapshot{at: time.Now(), epoch: s.epoch.Load()}
	after := ""
	submitted := 0
	for {
		if err := ctx.Err(); err != nil {
			return snap, submitted, err
		}
		rows, err := s.rowsInScope(ctx, scope, after)
		if err != nil {
			return snap, submitted, err
		}
		if err := ctx.Err(); err != nil {
			return snap, submitted, err
		}
		for _, r := range rows {
			if err := ctx.Err(); err != nil {
				return snap, submitted, err
			}
			snap.Total++
			// 命中只触碰缓存，不逐个 Stat 原件或检查祖先目录。
			if s.thumbs.HasStoredThumbnail(r.AssetID, thumbnail.SizeGrid, r.SizeBytes, r.Mtime) {
				snap.Covered++
				continue
			}
			if !submit {
				continue
			}
			src := filepath.Join(r.RootPath, filepath.FromSlash(r.RelPath))
			if !filing.PathWithinRoot(r.RootPath, src) {
				continue
			}
			task := thumbnail.Task{AssetID: r.AssetID, SourcePath: src, Kind: thumbnail.Kind(r.MediaType), Sizes: []thumbnail.Size{thumbnail.SizeGrid}}
			if err := s.submit(ctx, task); err != nil {
				if errors.Is(err, thumbnail.ErrSourceUnavailable) {
					// 删除/离线挂载等单资产失败不阻塞后续健康资产；周期全量仍会重试。
					s.logger.Warn("缩略图原件暂不可用，跳过本轮", "assetId", r.AssetID, "err", err)
					continue
				}
				return snap, submitted, err
			}
			submitted++
		}
		if len(rows) < pageSize {
			return snap, submitted, nil
		}
		after = rows[len(rows)-1].AssetID
	}
}

func (s *Service) submit(ctx context.Context, task thumbnail.Task) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := s.thumbs.Submit(task)
		if err == nil {
			return nil
		}
		if !errors.Is(err, thumbnail.ErrQueueFull) {
			return err
		}
		if !wait(ctx, retryPause) {
			return ctx.Err()
		}
	}
}

func wait(ctx context.Context, delay time.Duration) bool {
	if ctx.Err() != nil {
		return false
	}
	if delay <= 0 {
		return true
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
