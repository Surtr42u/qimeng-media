package scanner

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fsnotify/fsnotify"
	"golang.org/x/sync/errgroup"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

func TestWatchProbePanicReturnsError(t *testing.T) {
	e := newTestEnv(t, nil)
	e.s.probe = func(context.Context, string) (*thumbnail.ProbeResult, error) { panic("synthetic probe panic") }
	e.writeFile(t, "a.mp4", 17)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	err := e.s.Watch(ctx, e.lib)
	if err == nil || !strings.Contains(err.Error(), "synthetic probe panic") {
		t.Fatalf("子任务 panic 须退回监听主管，不能杀进程或泄漏接收任务: %v", err)
	}
}

// 外部移动的旧/新路径必须由同一完整视野合并，收藏不能因先删旧行而级联丢失。
func TestWatchMovePreservesIdentityAndFavorite(t *testing.T) {
	for _, directory := range []bool{false, true} {
		t.Run(fmt.Sprint("directory=", directory), func(t *testing.T) {
			e := newTestEnv(t, newProbeStub(nil, nil).call)
			source, target := "old/a.jpg", "new/a.jpg"
			e.writeFile(t, source, 17)
			if _, err := e.s.Scan(context.Background(), e.lib); err != nil {
				t.Fatal(err)
			}
			original := e.assetByPath(t, source)
			if _, err := e.conn.Exec("INSERT INTO favorites (asset_id, created_at) VALUES (?, ?)", original.AssetID, original.CreatedAt); err != nil {
				t.Fatal(err)
			}
			startWatch(t, e.s, e.lib, 50*time.Millisecond)
			if directory {
				if err := os.Rename(e.abs("old"), e.abs("new")); err != nil {
					t.Fatal(err)
				}
			} else {
				if err := os.Mkdir(e.abs("new"), 0o755); err != nil {
					t.Fatal(err)
				}
				if err := os.Rename(e.abs(source), e.abs(target)); err != nil {
					t.Fatal(err)
				}
			}
			eventually(t, 3*time.Second, "移动后路径应保留原身份", func() bool {
				asset, err := e.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{LibraryID: e.lib.ID, RelPath: target})
				return err == nil && asset.AssetID == original.AssetID
			})
			var count int
			if err := e.conn.QueryRow("SELECT COUNT(*) FROM favorites WHERE asset_id = ?", original.AssetID).Scan(&count); err != nil || count != 1 {
				t.Fatalf("移动丢失收藏: count=%d err=%v", count, err)
			}
			if e.assetCount(t) != 1 {
				t.Fatal("移动不应复制资产身份")
			}
		})
	}
}

func TestWatchImportsPrepopulatedDirectory(t *testing.T) {
	e := newTestEnv(t, newProbeStub(nil, nil).call)
	startWatch(t, e.s, e.lib, 50*time.Millisecond)
	// 监听注册前已有内容的目录整体移入，无需再写一次文件。
	staging := t.TempDir()
	if err := os.WriteFile(filepath.Join(staging, "a.jpg"), []byte("synthetic"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(staging, e.abs("incoming")); err != nil {
		t.Fatal(err)
	}
	eventually(t, 3*time.Second, "整体移入目录应主动对账", func() bool { return e.assetCount(t) == 1 })
}

func TestWatchWriteUsesSingleFileUpdate(t *testing.T) {
	e := newTestEnv(t, newProbeStub(nil, nil).call)
	e.writeFile(t, "a.jpg", 17)
	if _, err := e.s.Scan(context.Background(), e.lib); err != nil {
		t.Fatal(err)
	}
	sub := drainTopic(t, e.bus, events.TopicScanProgress)
	startWatch(t, e.s, e.lib, 50*time.Millisecond)
	eventually(t, 3*time.Second, "监听注册后应完成一次对账", func() bool {
		for _, event := range collect(sub) {
			if progress, ok := event.Payload.(scanProgressPayload); ok && progress.Phase == phaseReconciling {
				return true
			}
		}
		return false
	})
	// 单文件更新不会发全量扫描进度；这条断言验证实际选择的处理入口。
	e.writeFile(t, "a.jpg", 29)
	eventually(t, 3*time.Second, "已有文件应更新", func() bool { return e.assetByPath(t, "a.jpg").SizeBytes == 29 })
	if got := collect(sub); len(got) != 0 {
		t.Fatalf("单文件写入不应触发整库扫描: %+v", got)
	}
}

func TestWatchBatchBoundsAndFilters(t *testing.T) {
	var batch watchBatch
	for i := 0; i <= maxPendingPaths; i++ {
		batch.add(fmt.Sprint(i), false)
	}
	if !batch.full || len(batch.paths) != 0 {
		t.Fatal("事件风暴必须收敛为一次全量对账，不能保留无限路径")
	}
	e := newTestEnv(t, newProbeStub(nil, nil).call)
	e.s.dataDir = e.abs("private")
	w := watcher{s: e.s, lib: e.lib}
	for _, event := range []fsnotify.Event{
		{Name: filepath.Join(t.TempDir(), "outside.jpg"), Op: fsnotify.Create},
		{Name: e.abs("private/cache.webp"), Op: fsnotify.Create},
		{Name: e.abs(".hidden.jpg"), Op: fsnotify.Write},
		{Name: e.abs("notes.txt"), Op: fsnotify.Write},
		{Name: e.abs("a.jpg"), Op: fsnotify.Chmod},
	} {
		if _, relevant, err := w.classify(context.Background(), event); relevant || err != nil {
			t.Fatalf("无关事件不应扫描: %+v err=%v", event, err)
		}
	}
}

func TestWatchRetriesEventsDuringManualScan(t *testing.T) {
	probe := newProbeStub(nil, nil)
	probe.block = make(chan struct{})
	e := newTestEnv(t, probe.call)
	e.writeFile(t, "a.mp4", 17)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := e.s.Scan(ctx, e.lib); done <- err }()
	<-probe.entered // WalkDir 已枚举根目录后才添加新文件，当前扫描看不到它。
	startWatch(t, e.s, e.lib, 20*time.Millisecond)
	e.writeFile(t, "z.jpg", 29)
	close(probe.block)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	eventually(t, 3*time.Second, "扫描中收到的事件不得因忙而丢弃", func() bool { return e.assetCount(t) == 2 })
}

func TestBackgroundWatchLifecycleAndRetry(t *testing.T) {
	e := newTestEnv(t, newProbeStub(nil, nil).call)
	ctx, cancel := context.WithCancel(context.Background())
	workers, workerCtx := errgroup.WithContext(ctx)
	active := make(map[string]activeWatch)
	t.Cleanup(func() {
		cancel()
		for _, watch := range active {
			watch.cancel()
		}
		if err := workers.Wait(); err != nil {
			t.Error(err)
		}
	})
	e.s.syncWatches(workerCtx, workers, active, []db.Library{e.lib})
	first := active[e.lib.ID]
	e.s.syncWatches(workerCtx, workers, active, []db.Library{e.lib})
	if active[e.lib.ID].done != first.done {
		t.Fatal("同库不能重复启动监听")
	}
	e.s.syncWatches(workerCtx, workers, active, nil)
	select {
	case <-first.done:
	case <-time.After(3 * time.Second):
		t.Fatal("移除库应释放句柄和处理任务")
	}
	missing := e.lib
	missing.RootPath = filepath.Join(t.TempDir(), "later")
	e.s.syncWatches(workerCtx, workers, active, []db.Library{missing})
	failed := active[e.lib.ID]
	select {
	case <-failed.done:
	case <-time.After(3 * time.Second):
		t.Fatal("不可用根监听应返回，等待重试")
	}
	e.s.syncWatches(workerCtx, workers, active, []db.Library{missing})
	if active[e.lib.ID].done != failed.done {
		t.Fatal("失败冷却期间不能重复遍历注册")
	}
	if err := os.Mkdir(missing.RootPath, 0o755); err != nil {
		t.Fatal(err)
	}
	failed.retryAt = time.Time{} // 模拟冷却已到期，不实际等待 5 分钟。
	active[e.lib.ID] = failed
	e.s.syncWatches(workerCtx, workers, active, []db.Library{missing})
	if active[e.lib.ID].done == failed.done {
		t.Fatal("根恢复后应重建监听")
	}
	if err := os.WriteFile(filepath.Join(missing.RootPath, "recovered.jpg"), []byte("synthetic"), 0o600); err != nil {
		t.Fatal(err)
	}
	eventually(t, 3*time.Second, "重建后的监听必须实际处理文件", func() bool { return e.assetCount(t) == 1 })
}

func TestWatchContinuousEventsHaveBoundedDelay(t *testing.T) {
	e := newTestEnv(t, newProbeStub(nil, nil).call)
	e.s.watchDebounce = time.Hour // 不会自然进入静默，必须受最大期限约束。
	e.s.watchMaxDelay = 20 * time.Millisecond
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	eventsCh := make(chan fsnotify.Event)
	w := watcher{s: e.s, lib: e.lib, fw: &fsnotify.Watcher{Events: eventsCh, Errors: make(chan error)}}
	batches := make(chan watchBatch)
	group, workCtx := errgroup.WithContext(ctx)
	group.Go(func() error { return w.receive(workCtx, batches) })
	select {
	case <-batches: // 注册后的初次对账也不能被异常防抖值拖住。
	case <-ctx.Done():
		t.Fatal("初次批次未受最大等待约束")
	}
	group.Go(func() error {
		for {
			select {
			case eventsCh <- fsnotify.Event{Name: e.abs("a.jpg"), Op: fsnotify.Write}:
			case <-workCtx.Done():
				return workCtx.Err()
			}
		}
	})
	select {
	case <-batches:
	case <-ctx.Done():
		t.Error("持续事件不能无限推迟处理")
	}
	cancel()
	if err := group.Wait(); !errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("取消应结束所有事件任务: %v", err)
	}
}

func TestBackgroundScanWaitIsCancellable(t *testing.T) {
	e := newTestEnv(t, newProbeStub(nil, nil).call)
	for range 32 {
		release, err := e.s.admission.acquire(context.Background(), func(context.Context) int { return 32 })
		if err != nil {
			t.Fatal(err)
		}
		defer release()
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- e.s.scanBackground(ctx, e.lib, "测试") }()
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("等待名额应可取消: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("后台扫描等待泄漏")
	}
}

func TestWatchCancelWhileManualScanOwnsGate(t *testing.T) {
	e := newTestEnv(t, newProbeStub(nil, nil).call)
	e.writeFile(t, "a.jpg", 17)
	e.s.watchDebounce = 20 * time.Millisecond
	sub := drainTopic(t, e.bus, events.TopicScanProgress)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		if err := e.s.Watch(ctx, e.lib); err != nil {
			t.Errorf("Watch: %v", err)
		}
	}()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("监听清理超时")
		}
	})
	eventually(t, 3*time.Second, "监听初次对账", func() bool {
		for _, event := range collect(sub) {
			if progress, ok := event.Payload.(scanProgressPayload); ok && progress.Phase == phaseReconciling {
				return true
			}
		}
		return false
	})
	g := e.s.gate(e.lib.ID)
	eventually(t, time.Second, "模拟独立的手动扫描持锁", g.beginScan)
	defer g.endScan()
	e.writeFile(t, "a.jpg", 29)
	time.Sleep(100 * time.Millisecond) // 让写事件进入处理，此时手动扫描尚未结束。
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("取消不应等待独立手动扫描释放库闸门")
	}
}

// 同时启动两个库的后台全扫，首个探测被阻塞时第二个不能开始探测。
func TestBackgroundScansShareOneSlot(t *testing.T) { checkBackgroundScanConcurrency(t, 1) }

func TestBackgroundScansRunTogether(t *testing.T) { checkBackgroundScanConcurrency(t, 2) }

func checkBackgroundScanConcurrency(t *testing.T, workers int) {
	probe := newProbeStub(nil, nil)
	probe.block = make(chan struct{})
	e := newTestEnv(t, probe.call)
	if err := e.q.UpsertSetting(context.Background(), db.UpsertSettingParams{Key: "client_config", Value: fmt.Sprintf(`{"scan":{"workers":%d}}`, workers), UpdatedAt: store.FormatTimestamp(time.Now())}); err != nil {
		t.Fatal(err)
	}
	e.writeFile(t, "a.mp4", 17)
	other := e.lib
	other.ID, other.RootPath = "synthetic-other", t.TempDir()
	other, err := e.q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: other.ID, Name: "other", RootPath: other.RootPath, Kind: LibraryKindNormal, CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(other.RootPath, "b.mp4"), []byte("synthetic"), 0o600); err != nil {
		t.Fatal(err)
	}
	group, ctx := errgroup.WithContext(context.Background())
	group.Go(func() error { return e.s.scanBackground(ctx, e.lib, "测试") })
	<-probe.entered
	group.Go(func() error { return e.s.scanBackground(ctx, other, "测试") })
	if workers == 1 {
		select {
		case <-probe.entered:
			t.Error("单名额时第二个库不应开始")
		case <-time.After(50 * time.Millisecond):
		}
	} else {
		select {
		case <-probe.entered:
		case <-time.After(time.Second):
			t.Error("并发设置没有实际放行第二个库")
		}
	}
	close(probe.block)
	if err := group.Wait(); err != nil {
		t.Fatal(err)
	}
	if probe.calls.Load() != 2 {
		t.Fatalf("两个库应依次完成: %d", probe.calls.Load())
	}
}
