package thumbnail

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func waitForeground(t *testing.T, s *semaphore) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for {
		s.mu.Lock()
		waiting := s.foregroundWaiting
		s.mu.Unlock()
		if waiting > 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("前台未进入等候")
		}
		time.Sleep(time.Millisecond)
	}
}

func TestBackgroundReservesForegroundCapacityAndPromotes(t *testing.T) {
	s := newSemaphore(2)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if !s.acquirePriority(ctx, nil) {
		t.Fatal("首个后台未获准入")
	}
	priority := make(chan struct{})
	done := make(chan bool, 1)
	go func() { done <- s.acquirePriority(ctx, priority) }()
	// 后台无法借用保留位，提升为前台后无需等现有后台结束。
	select {
	case ok := <-done:
		if ok {
			s.release()
		}
		t.Fatal("后台占用前台保留位")
	case <-time.After(20 * time.Millisecond):
	}
	close(priority)
	if ok := <-done; !ok {
		t.Fatal("提升后未使用保留位")
	}
	s.release()
	s.release()
}

func TestSingleCoreWaitsForRunningWorkThenPrefersForeground(t *testing.T) {
	s := newSemaphore(1)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if !s.acquirePriority(ctx, nil) {
		t.Fatal("单核必须允许后台生成")
	}
	bg := make(chan bool, 1)
	go func() { bg <- s.acquirePriority(ctx, nil) }()
	fg := make(chan bool, 1)
	go func() { fg <- s.acquire(ctx) }()
	waitForeground(t, s)
	s.release()
	if ok := <-fg; !ok {
		t.Fatal("前台未优先获准入")
	}
	select {
	case ok := <-bg:
		if ok {
			s.release()
		}
		t.Fatal("前台持闸时后台获准入")
	default:
	}
	s.release()
	if ok := <-bg; !ok {
		t.Fatal("前台完成后后台未恢复")
	}
	s.release()
}

func TestCancelledForegroundDoesNotBlockBackground(t *testing.T) {
	s := newSemaphore(1)
	s.acquire(context.Background())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan bool, 1)
	go func() { done <- s.acquire(ctx) }()
	waitForeground(t, s)
	cancel()
	if <-done {
		t.Fatal("取消后仍准入")
	}
	s.release()
	if !s.acquirePriority(context.Background(), nil) {
		t.Fatal("取消者遗留前台占位")
	}
	s.release()
}

func TestForegroundPromotesSharedGeneratorWaitingInBackground(t *testing.T) {
	slots := newSemaphore(2)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if !slots.acquirePriority(ctx, nil) {
		t.Fatal("无法占后台名额")
	}
	defer slots.release()
	g := bareGenerator(t, int(SizeGrid))
	g.slots = slots
	src := filepath.Join(t.TempDir(), "source.bin")
	writeTestFile(t, src, "source")
	version, err := g.sourceVersion(src)
	if err != nil {
		t.Fatal(err)
	}
	dst := g.thumbDst("asset", SizeGrid, version)
	bg := make(chan error, 1)
	go func() {
		bg <- g.handle(ctx, Task{AssetID: "asset", SourcePath: src, Kind: "invalid", Sizes: []Size{SizeGrid}})
	}()
	waitGenerating(t, g, dst)
	err = g.EnsureDetached(ctx, "asset", src, "invalid", []Size{SizeGrid})
	// 未起 FFmpeg；未知类型错误证明共用生成体获得保留位而非等到超时。
	if err == nil || errors.Is(err, context.DeadlineExceeded) || !strings.Contains(err.Error(), "未知媒体类型") {
		t.Fatalf("提升失败: %v", err)
	}
	if err := <-bg; err == nil || !strings.Contains(err.Error(), "未知媒体类型") {
		t.Fatalf("后台结果未共用: %v", err)
	}
	waitTermsRecycled(t, g.generating, dst)
}
