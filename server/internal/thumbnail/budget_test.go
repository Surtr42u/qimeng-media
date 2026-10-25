package thumbnail

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

type observeBudgetWait struct {
	context.Context
	entered chan struct{}
	once    sync.Once
}

func (c *observeBudgetWait) Done() <-chan struct{} {
	c.once.Do(func() { close(c.entered) })
	return c.Context.Done()
}

func TestRepresentationBudgetCountsOverlapOnceAndRetries(t *testing.T) {
	b := newRepresentationBudget(2)
	a, err := b.acquire(t.Context(), []string{"a", "b", "a"}, false)
	if err != nil {
		t.Fatal(err)
	}
	defer a.release()
	joined, err := b.acquire(t.Context(), []string{"b"}, false)
	if err != nil {
		t.Fatal("overlap charged twice", err)
	}
	if _, err := b.acquire(t.Context(), []string{"c"}, false); !errors.Is(err, ErrQueueFull) {
		t.Fatal("accepted overflow", err)
	}
	a.release()
	c, err := b.acquire(t.Context(), []string{"c"}, false)
	if err != nil {
		t.Fatal("retry did not use freed key", err)
	}
	c.release()
	joined.release()
	if len(b.refs) != 0 {
		t.Fatal("lease leaked")
	}
}

func TestRepresentationAdmissionCancelsWithoutCreatingWork(t *testing.T) {
	b := newRepresentationBudget(1)
	a, err := b.acquire(t.Context(), []string{"a"}, false)
	if err != nil {
		t.Fatal(err)
	}
	defer a.release()
	ctx, cancel := context.WithCancel(t.Context())
	waiting := &observeBudgetWait{Context: ctx, entered: make(chan struct{})}
	done := make(chan error, 1)
	go func() { _, err := b.acquire(waiting, []string{"b"}, true); done <- err }()
	<-waiting.entered
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if len(b.refs) != 1 || b.refs["a"] != 1 {
		t.Fatal("canceled admission mutated budget")
	}
	ctx2 := &observeBudgetWait{Context: t.Context(), entered: make(chan struct{})}
	go func() { _, err := b.acquire(ctx2, []string{"b"}, true); done <- err }()
	<-ctx2.entered
	b.close()
	if err := <-done; !errors.Is(err, ErrPoolClosed) {
		t.Fatal("close did not notify admission", err)
	}
}

func TestDifferentRepresentationsStayBoundedUnderCanceledWaiters(t *testing.T) {
	const limit = 8
	b := newRepresentationBudget(limit)
	dg := newDetachedGroup()
	release := make(chan struct{})
	var unblock sync.Once
	defer func() { unblock.Do(func() { close(release) }); dg.stop(); dg.wg.Wait() }()
	for i := range limit {
		key := fmt.Sprint(i)
		lease, err := b.acquire(t.Context(), []string{key}, false)
		if err != nil {
			t.Fatal(err)
		}
		started := make(chan struct{})
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan error, 1)
		go func() {
			done <- dg.doOwned(key, ctx, false, lease, func(<-chan struct{}) error { close(started); <-release; return nil })
		}()
		<-started
		cancel()
		if err := <-done; !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	}
	for i := range 2000 {
		if _, err := b.acquire(t.Context(), []string{fmt.Sprint(i + limit)}, false); !errors.Is(err, ErrQueueFull) {
			t.Fatal("unbounded admission", err)
		}
	}
	if len(b.refs) != limit {
		t.Fatal("different-key budget exceeded")
	}
	unblock.Do(func() { close(release) })
	dg.wg.Wait()
	if len(b.refs) != 0 {
		t.Fatal("accepted canceled work leaked")
	}
}

func TestGeneratorCloseCollectsDetachedAndQueuedWork(t *testing.T) {
	for _, cores := range []int{1, 4} {
		t.Run(fmt.Sprint(cores), func(t *testing.T) {
			g := bareGenerator(t, int(SizeSmall))
			g.initLifecycle()
			g.slots = newSemaphore(cores)
			// 占住全部执行名额；关闭必须取消等闸体，不能靠外部放行才结束。
			for range cores {
				if !g.slots.acquire(t.Context()) {
					t.Fatal("slot")
				}
			}
			defer func() {
				for range cores {
					g.slots.release()
				}
			}()
			g.pool = NewWorkerPool(g.life, 1, 2, g.handle, g.logger)
			src := filepath.Join(t.TempDir(), "source.bin")
			writeTestFile(t, src, "synthetic")
			version, err := g.sourceVersion(src)
			if err != nil {
				t.Fatal(err)
			}
			if err := g.Submit(Task{AssetID: "queued", SourcePath: src, Kind: "invalid", Sizes: []Size{SizeGrid}}); err != nil {
				t.Fatal(err)
			}
			waitGenerating(t, g, g.thumbDst("queued", SizeGrid, version))
			done := make(chan error, 1)
			go func() {
				done <- g.EnsureDetached(t.Context(), "foreground", src, "invalid", []Size{SizeGrid, SizeSmall})
			}()
			waitGenerating(t, g, g.thumbDst("foreground", SizeGrid, version))
			if err := g.CloseContext(t.Context()); err != nil {
				t.Fatal(err)
			}
			if err := <-done; err == nil {
				t.Fatal("shutdown falsely reported generated cache")
			}
			if err := g.CloseContext(t.Context()); err != nil {
				t.Fatal(err)
			}
			if err := g.Submit(Task{AssetID: "late", SourcePath: src, Sizes: []Size{SizeGrid}}); !errors.Is(err, ErrPoolClosed) {
				t.Fatal("accepted after close", err)
			}
			g.budget.mu.Lock()
			defer g.budget.mu.Unlock()
			if len(g.budget.refs) != 0 {
				t.Fatal("close leaked representations")
			}
		})
	}
}

func TestPanicReturnsRepresentationLease(t *testing.T) {
	b := newRepresentationBudget(1)
	lease, err := b.acquire(t.Context(), []string{"a"}, false)
	if err != nil {
		t.Fatal(err)
	}
	dg := newDetachedGroup()
	if err := dg.doOwned("a", t.Context(), false, lease, func(<-chan struct{}) error { panic("synthetic") }); err == nil {
		t.Fatal("panic suppressed")
	}
	dg.wg.Wait()
	if len(b.refs) != 0 {
		t.Fatal("panic leaked lease")
	}
}

func TestSubmitClassifiesUnavailableSourceBeforeAdmission(t *testing.T) {
	g := bareGenerator(t, int(SizeSmall))
	defer g.Close()
	err := g.Submit(Task{AssetID: "offline", SourcePath: filepath.Join(t.TempDir(), "missing.jpg"), Sizes: []Size{SizeGrid}})
	if !errors.Is(err, ErrSourceUnavailable) {
		t.Fatal("unclassified source failure", err)
	}
	if len(g.budget.refs) != 0 {
		t.Fatal("unavailable source reserved budget")
	}
}

func TestQueuedVersionCacheHitStillChecksSourceAndKeepsCurrentCache(t *testing.T) {
	g := bareGenerator(t, int(SizeSmall))
	defer g.Close()
	src := filepath.Join(t.TempDir(), "source.bin")
	writeTestFile(t, src, "old")
	old, err := g.sourceVersion(src)
	if err != nil {
		t.Fatal(err)
	}
	oldDst := g.thumbDst("asset", SizeGrid, old)
	writeTestFile(t, oldDst, "old-cache")
	writeTestFile(t, src, "new-longer")
	current, err := g.sourceVersion(src)
	if err != nil {
		t.Fatal(err)
	}
	currentDst := g.thumbDst("asset", SizeGrid, current)
	writeTestFile(t, currentDst, "current-cache")
	if err := g.ensureVersion(t.Context(), "asset", src, "invalid", []Size{SizeGrid}, old, false); err == nil {
		t.Fatal("stale queued cache bypassed source check")
	}
	if _, err := os.Stat(oldDst); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("stale cache remains", err)
	}
	if raw, err := os.ReadFile(currentDst); err != nil || string(raw) != "current-cache" {
		t.Fatal("current cache removed", err)
	}
}
