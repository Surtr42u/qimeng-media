package scanner

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"time"

	"github.com/fsnotify/fsnotify"
	"golang.org/x/sync/errgroup"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/safego"
	"qimeng-media/server/internal/store/db"
)

const (
	// DefaultDebounce 合并同库的事件风暴，避免每个文件独立启动定时器。
	DefaultDebounce = 300 * time.Millisecond
	// DefaultPollInterval 保留 5min 兜底：网络挂载可能静默漏事件，
	// 不能仅凭监听注册成功就自动拉长扫描周期。配置可显式覆盖。
	DefaultPollInterval = config.DefaultScannerPollInterval
	// maxPendingPaths 大批复制时退回一次全量对账，限制事件队列内存。
	maxPendingPaths = 256
)

type watchBatch struct {
	full  bool
	paths map[string]struct{}
}

func (b *watchBatch) add(path string, full bool) {
	if b.full {
		return
	}
	if full {
		b.full, b.paths = true, nil
		return
	}
	if _, exists := b.paths[path]; exists {
		return
	}
	if len(b.paths) >= maxPendingPaths {
		b.full, b.paths = true, nil
		return
	}
	if b.paths == nil {
		b.paths = make(map[string]struct{})
	}
	b.paths[path] = struct{}{}
}

func (b watchBatch) empty() bool { return !b.full && len(b.paths) == 0 }

type watcher struct {
	s      *Scanner
	lib    db.Library
	fw     *fsnotify.Watcher
	logger *slog.Logger
}

// Watch 递归监听一个库，直到取消或监听失效。接收事件与串行处理分离，
// 慢扫描期间继续收集事件；队列至多一批，路径超限则合并为一次全量扫描。
// 已有文件 Write 走单文件更新；新增/删除/改名必须整库对账，不能逐事件
// 先删旧身份或先入新身份，否则移动合并会丢失收藏/标签等关联。
func (s *Scanner) Watch(ctx context.Context, lib db.Library) error {
	fw, err := fsnotify.NewWatcher()
	if err != nil {
		return fmt.Errorf("scanner: 创建 fsnotify watcher: %w", err)
	}
	defer func() {
		if err := fw.Close(); err != nil {
			s.logger.Warn("scanner: 关闭监听失败", "libraryId", lib.ID, "err", err)
		}
	}()
	if err := s.ensureLibraryRoot(ctx, &lib); err != nil {
		return fmt.Errorf("scanner: 注册库监听: %w", err)
	}
	w := &watcher{s: s, lib: lib, fw: fw, logger: s.logger.With("libraryId", lib.ID)}
	if err := w.addTree(ctx, lib.RootPath); err != nil {
		return fmt.Errorf("scanner: 注册库监听: %w", err)
	}
	w.logger.Info("scanner: 增量监听已启动")
	group, workCtx := errgroup.WithContext(ctx)
	batches := make(chan watchBatch)
	group.Go(func() error {
		return safego.Run(w.logger, "监听事件接收", func() error { return w.receive(workCtx, batches) })
	})
	group.Go(func() error {
		return safego.Run(w.logger, "监听批次处理", func() error { return w.process(workCtx, batches) })
	})
	err = group.Wait()
	if ctx.Err() != nil {
		return nil
	}
	return err
}

// receive 只在此 goroutine 管理防抖状态。固定窗口保证连续写入也不会
// 永远推迟处理；worker 忙时待处理批次留在本地合并，不增加 goroutine。
func (w *watcher) receive(ctx context.Context, batches chan<- watchBatch) error {
	debounce := w.s.watchDebounce
	if debounce <= 0 {
		debounce = DefaultDebounce
	}
	timer := time.NewTimer(debounce)
	defer timer.Stop()
	// 注册后对账一次，补进程停机期间及目录注册窗口内的变化。
	pending := watchBatch{full: true}
	var send chan<- watchBatch
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			send = batches
		case send <- pending:
			pending = watchBatch{}
			send = nil
		case err, ok := <-w.fw.Errors:
			if !ok {
				return errors.New("scanner: 监听错误通道关闭")
			}
			// 溢出或后端故障：退出交主管重建整个目录监听树；轮询仍独立运行。
			return fmt.Errorf("scanner: 文件监听失效: %w", err)
		case ev, ok := <-w.fw.Events:
			if !ok {
				return errors.New("scanner: 监听事件通道关闭")
			}
			full, relevant, err := w.classify(ctx, ev)
			if err != nil {
				return err
			}
			if !relevant {
				continue
			}
			if pending.empty() {
				timer.Reset(debounce)
			}
			pending.add(ev.Name, full)
		}
	}
}

// classify 对目录新增先补注册再整库对账，覆盖“目录带着文件一起移入”。
// 不跟随链接或数据目录；忽略无关文件的写入与 Chmod 噪音。
func (w *watcher) classify(ctx context.Context, ev fsnotify.Event) (full, relevant bool, err error) {
	if w.s.dataDir != "" && w.s.pathInsideDataDir(ev.Name) {
		return false, false, nil
	}
	if !filing.PathWithinRoot(w.lib.RootPath, ev.Name) {
		return false, false, nil
	}
	if ev.Has(fsnotify.Create) {
		if info, statErr := os.Lstat(ev.Name); statErr == nil && info.IsDir() {
			if SkipDir(info.Name()) {
				return false, false, nil
			}
			return true, true, w.addTree(ctx, ev.Name)
		}
	}
	if ev.Has(fsnotify.Remove) || ev.Has(fsnotify.Rename) {
		// 已消失的目录不能 Stat；即使无媒体扩展名也要整库对账。
		return true, true, nil
	}
	if IsHiddenName(filepath.Base(ev.Name)) {
		return false, false, nil
	}
	if _, ok := ClassifyMedia(ev.Name); !ok {
		return false, false, nil
	}
	return ev.Has(fsnotify.Create), ev.Has(fsnotify.Create) || ev.Has(fsnotify.Write), nil
}

func (w *watcher) addTree(ctx context.Context, root string) error {
	return filepath.WalkDir(root, func(path string, d fs.DirEntry, walkErr error) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if walkErr != nil {
			return walkErr // 部分注册也视为失败，由主管重试，不能假装监听完整。
		}
		if !d.IsDir() {
			return nil
		}
		if (path != w.lib.RootPath && SkipDir(d.Name())) ||
			(w.s.dataDir != "" && w.s.pathInsideDataDir(path)) ||
			!filing.PathWithinRoot(w.lib.RootPath, path) {
			return filepath.SkipDir
		}
		return w.fw.Add(path)
	})
}

func (w *watcher) process(ctx context.Context, batches <-chan watchBatch) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case batch := <-batches:
			full := batch.full
			for path := range batch.paths {
				if w.updateKnownFile(ctx, path) {
					full = true
					break
				}
			}
			if full {
				if err := w.scan(ctx); err != nil && ctx.Err() == nil {
					w.logger.Warn("scanner: 增量对账失败（轮询兜底）", "err", err)
				}
			}
		}
	}
}

// scan 遇手动/轮询扫描正在运行时等待后重试，不能吞掉扫描中途到达的事件。
func (w *watcher) scan(ctx context.Context) error {
	for {
		err := w.s.scanBackground(ctx, w.lib, "增量对账")
		if !errors.Is(err, ErrAlreadyScanning) {
			return err
		}
		timer := time.NewTimer(DefaultDebounce)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}
