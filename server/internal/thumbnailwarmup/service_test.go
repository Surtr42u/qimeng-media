package thumbnailwarmup

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

type observedWaitContext struct {
	context.Context
	entered chan struct{}
	once    sync.Once
}

func (c *observedWaitContext) Done() <-chan struct{} {
	c.once.Do(func() { close(c.entered) })
	return c.Context.Done()
}

func TestProgressWaiterCancellationDoesNotWaitForActiveQuery(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	q := candidateFunc(func(ctx context.Context, p db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
		calls.Add(1)
		close(entered)
		<-release
		return nil, nil
	})
	s := New(q, fakeThumbs{}, nil, time.Now())
	first := make(chan error, 1)
	go func() { _, err := s.Progress(t.Context()); first <- err }()
	<-entered
	ctx, cancel := context.WithCancel(t.Context())
	waiting := &observedWaitContext{Context: ctx, entered: make(chan struct{})}
	second := make(chan error, 1)
	go func() { _, err := s.Progress(waiting); second <- err }()
	<-waiting.entered // 已实际进入被占用闸的 select，再取消，而非预先传已取消 context。
	cancelStarted := time.Now()
	cancel()
	if err := <-second; !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel did not leave waiting gate: %v", err)
	}
	t.Logf("progress_cancel_wait_us=%d active_queries=%d", time.Since(cancelStarted).Microseconds(), calls.Load())
	if calls.Load() != 1 {
		t.Fatal("canceled waiter started another query")
	}
	close(release)
	if err := <-first; err != nil {
		t.Fatal(err)
	}
	if _, err := s.Progress(t.Context()); err != nil || calls.Load() != 1 {
		t.Fatal("waiter cancellation disturbed shared successful snapshot")
	}
}

type candidateFunc func(context.Context, db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error)

func (fn candidateFunc) ListThumbnailWarmupPage(ctx context.Context, p db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
	return fn(ctx, p)
}

type fakeThumbs struct {
	has    func(string, int64, string) bool
	submit func(thumbnail.Task) error
}

func (f fakeThumbs) HasStoredThumbnail(id string, _ thumbnail.Size, bytes int64, mtime string) bool {
	return f.has(id, bytes, mtime)
}
func (f fakeThumbs) Submit(task thumbnail.Task) error { return f.submit(task) }

func TestProgressPagesEntireLibraryAndSharesReconciliationSnapshot(t *testing.T) {
	const total = 20000
	calls, checks := 0, 0
	q := candidateFunc(func(ctx context.Context, p db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
		calls++
		if p.PageSize != pageSize {
			t.Fatal("查询未限制批大小")
		}
		start := 0
		if p.AfterID != "" {
			var err error
			start, err = strconv.Atoi(p.AfterID)
			if err != nil {
				t.Fatal(err)
			}
		}
		var rows []db.ListThumbnailWarmupPageRow
		for i := start + 1; i <= total && len(rows) < pageSize; i++ {
			rows = append(rows, db.ListThumbnailWarmupPageRow{AssetID: fmt.Sprintf("%06d", i), RootPath: "root-does-not-exist", SizeBytes: int64(i), Mtime: "stored"})
		}
		return rows, nil
	})
	thumbs := fakeThumbs{has: func(id string, bytes int64, mtime string) bool {
		checks++
		if bytes == 0 || mtime != "stored" {
			t.Fatal("元数据未传递")
		}
		return true
	}, submit: func(thumbnail.Task) error { t.Fatal("已覆盖资产不应投递"); return nil }}
	s := New(q, thumbs, nil, time.Time{})
	s.reconcile(context.Background())
	snap, err := s.Progress(context.Background())
	if err != nil || snap.Total != total || snap.Covered != total {
		t.Fatalf("覆盖: %+v %v", snap, err)
	}
	if calls != (total+pageSize-1)/pageSize || checks != total {
		t.Fatalf("缓存未复用或遗漏分页: calls=%d checks=%d", calls, checks)
	}
	// 变更后不能继续发布旧统计；通知缓冲必须有界。
	for i := 0; i < 1000; i++ {
		s.Notify()
	}
	if len(s.wake) != 1 {
		t.Fatal("通知未合并")
	}
	if _, ok := s.fresh(); ok {
		t.Fatal("旧进度未失效")
	}
	if _, err := s.Progress(context.Background()); err != nil {
		t.Fatal(err)
	}
	if checks != total*2 {
		t.Fatal("变更未重新统计")
	}
}

func TestMissingTasksStreamSafelyAndClosedPoolStops(t *testing.T) {
	root := t.TempDir()
	q := candidateFunc(func(context.Context, db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
		return []db.ListThumbnailWarmupPageRow{
			{AssetID: "unsafe", RootPath: root, RelPath: "../escape.bin"},
			{AssetID: "safe", RootPath: root, RelPath: "image.png", MediaType: "image"},
			{AssetID: "later", RootPath: root, RelPath: "later.png"},
		}, nil
	})
	submitted := 0
	s := New(q, fakeThumbs{has: func(string, int64, string) bool { return false }, submit: func(task thumbnail.Task) error {
		submitted++
		if task.AssetID != "safe" || task.SourcePath != filepath.Join(root, "image.png") || task.Kind != thumbnail.KindImage {
			t.Fatalf("不安全或错误任务: %+v", task)
		}
		return thumbnail.ErrPoolClosed
	}}, nil, time.Time{})
	_, _, err := s.inspect(context.Background(), true)
	if !errors.Is(err, thumbnail.ErrPoolClosed) || submitted != 1 {
		t.Fatalf("关闭后继续投递: %v %d", err, submitted)
	}
}

func TestQueueBackpressureAndBootWaitCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	s := New(nil, fakeThumbs{submit: func(thumbnail.Task) error { cancel(); return thumbnail.ErrQueueFull }}, nil, time.Now().Add(time.Hour))
	if err := s.submit(ctx, thumbnail.Task{}); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	// 已取消的开机静默窗不能开始查库。
	s.Run(ctx)
}

func TestAcceptedTaskDoesNotWaitForFixedPause(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	calls := 0
	s := New(nil, fakeThumbs{submit: func(thumbnail.Task) error { calls++; cancel(); return nil }}, nil, time.Time{})
	// 队列已接受即成功，取消只阻止后续投递；不能再经过固定睡眠变成失败。
	if err := s.submit(ctx, thumbnail.Task{}); err != nil {
		t.Fatal(err)
	}
	if err := s.submit(ctx, thumbnail.Task{}); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatal("取消后仍继续提交")
	}
}

func TestNotificationDuringReconciliationRunsAnotherPass(t *testing.T) {
	entered := make(chan struct{}, 2)
	release := make(chan struct{})
	calls := 0
	q := candidateFunc(func(ctx context.Context, _ db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
		calls++
		entered <- struct{}{}
		if calls == 1 {
			select {
			case <-release:
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		}
		return nil, nil
	})
	s := New(q, fakeThumbs{}, nil, time.Time{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { s.run(ctx, time.Millisecond); close(done) }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("未启动回填")
	}
	for i := 0; i < 10; i++ {
		s.Notify()
	}
	close(release)
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("运行中的变更被丢弃")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("主管未随停机退出")
	}
	if calls != 2 {
		t.Fatalf("通知未合并: %d", calls)
	}
}

func TestChangeCooldownMergesAndCancelsWithoutAnotherQuery(t *testing.T) {
	entered := make(chan struct{}, 2)
	q := candidateFunc(func(context.Context, db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
		entered <- struct{}{}
		return nil, nil
	})
	s := New(q, fakeThumbs{}, nil, time.Time{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { s.Run(ctx); close(done) }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("未启动")
	}
	for i := 0; i < 100; i++ {
		s.Notify()
	}
	select {
	case <-entered:
		t.Fatal("冷却窗内重复扫描")
	case <-time.After(20 * time.Millisecond):
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("冷却等待未取消")
	}
	select {
	case <-entered:
		t.Fatal("取消后仍查询")
	default:
	}
}

func TestConcurrentProgressFailureAndNotifyDuringRead(t *testing.T) {
	var s *Service
	calls := 0
	q := candidateFunc(func(context.Context, db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
		calls++
		if calls == 1 {
			return nil, errors.New("query failed")
		}
		if calls == 2 {
			s.Notify()
		}
		return nil, nil
	})
	s = New(q, fakeThumbs{}, nil, time.Time{})
	if _, err := s.Progress(context.Background()); err == nil {
		t.Fatal("查询失败被吞掉")
	}
	if _, err := s.Progress(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.fresh(); ok {
		t.Fatal("扫描中变更仍发布了旧快照")
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Go(func() {
			if _, err := s.Progress(context.Background()); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	if calls != 3 {
		t.Fatalf("并发进度未合并: %d", calls)
	}
}

func TestUnavailableSourceDoesNotStarveLaterWarmupAssets(t *testing.T) {
	root := t.TempDir()
	q := candidateFunc(func(context.Context, db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
		return []db.ListThumbnailWarmupPageRow{
			{AssetID: "a-offline", RootPath: root, RelPath: "offline.jpg"},
			{AssetID: "b-healthy", RootPath: root, RelPath: "healthy.jpg"},
		}, nil
	})
	var visited []string
	s := New(q, fakeThumbs{has: func(string, int64, string) bool { return false }, submit: func(task thumbnail.Task) error {
		visited = append(visited, task.AssetID)
		if task.AssetID == "a-offline" {
			return fmt.Errorf("synthetic stat: %w", thumbnail.ErrSourceUnavailable)
		}
		return nil
	}}, nil, time.Now())
	snap, submitted, err := s.inspect(t.Context(), true)
	if err != nil || snap.Total != 2 || submitted != 1 || len(visited) != 2 || visited[1] != "b-healthy" {
		t.Fatalf("offline asset blocked traversal: %+v %d %v %v", snap, submitted, visited, err)
	}
}
