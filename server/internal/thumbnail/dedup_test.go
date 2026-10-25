package thumbnail

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func bareGenerator(t *testing.T, longSide int) *Generator {
	t.Helper()
	return &Generator{dataDir: t.TempDir(), longSide: longSide, logger: slog.Default(),
		detached: newDetachedGroup(), generating: newDetachedGroup(), slots: genSlots}
}

func writeTestFile(t *testing.T, path, data string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
		t.Fatal(err)
	}
}

func waitGenerating(t *testing.T, g *Generator, dst string) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		g.generating.mu.Lock()
		present := g.generating.terms[dst] != nil
		g.generating.mu.Unlock()
		if present {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("生成未进入共用单飞")
}

func waitGroupIdle(t *testing.T, dg *detachedGroup) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for {
		dg.mu.Lock()
		idle := len(dg.terms) == 0
		dg.mu.Unlock()
		if idle {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("续生任务未结束")
		}
		time.Sleep(time.Millisecond)
	}
}

func TestForegroundAndPoolShareEffectiveSizeAndRecheckCache(t *testing.T) {
	slots := newSemaphore(1)
	slots.acquire(context.Background())
	// 未放行前测试失败也归还闸，避免工作池清理阻塞。
	released := false
	t.Cleanup(func() {
		if !released {
			slots.release()
		}
	})
	g := bareGenerator(t, int(SizeSmall))
	g.slots = slots
	src := filepath.Join(t.TempDir(), "source.bin")
	writeTestFile(t, src, "source")
	version, err := g.sourceVersion(src)
	if err != nil {
		t.Fatal(err)
	}
	dst := g.thumbDst("asset", SizeGrid, version)
	workerDone := make(chan error, 1)
	g.pool = NewWorkerPool(context.Background(), 1, 1, func(ctx context.Context, task Task) error {
		err := g.handle(ctx, task)
		workerDone <- err
		return err
	}, g.logger)
	// 故意使用未知类型：缓存复查失效时会报错，绝不会依赖测试机上的 ffmpeg。
	if err := g.Submit(Task{AssetID: "asset", SourcePath: src, Kind: "invalid", Sizes: []Size{SizeGrid}}); err != nil {
		t.Fatal(err)
	}
	waitGenerating(t, g, dst)
	ctx, cancel := context.WithCancel(context.Background())
	callerDone := make(chan error, 1)
	go func() { callerDone <- g.EnsureDetached(ctx, "asset", src, "invalid", []Size{SizeSmall, SizeGrid}) }()
	cancel()
	if err := <-callerDone; !errors.Is(err, context.Canceled) {
		t.Fatalf("等待者取消: %v", err)
	}
	// 在等闸期间发布缓存，同一次实际像素生成不能再起转码。
	writeTestFile(t, dst, "cached")
	slots.release()
	released = true
	if err := <-workerDone; err != nil {
		t.Fatal(err)
	}
	g.Close()
	// 请求已取消仍可能续生后续尺寸，结束前收齐外层调用，再等实际生成组清理。
	waitGroupIdle(t, g.detached)
	waitGroupIdle(t, g.generating)
	if err := g.EnsureDetached(context.Background(), "asset", src, "invalid", []Size{SizeGrid, SizeSmall}); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(dst); err != nil || string(data) != "cached" {
		t.Fatal("缓存被重复生成或覆盖", err)
	}
	waitTermsRecycled(t, g.generating, dst)
}

func TestDetachedGenerationStillRemovesChangedSourceVersion(t *testing.T) {
	slots := newSemaphore(1)
	slots.acquire(context.Background())
	g := bareGenerator(t, int(SizeGrid))
	g.slots = slots
	src := filepath.Join(t.TempDir(), "source.bin")
	writeTestFile(t, src, "source")
	version, err := g.sourceVersion(src)
	if err != nil {
		t.Fatal(err)
	}
	dst := g.thumbDst("asset", SizeGrid, version)
	done := make(chan error, 1)
	go func() { done <- g.Ensure(context.Background(), "asset", src, "invalid", []Size{SizeGrid}) }()
	waitGenerating(t, g, dst)
	writeTestFile(t, dst, "stale")
	writeTestFile(t, src, "replacement-longer")
	slots.release()
	if err := <-done; err == nil {
		t.Fatal("原件换代必须失败")
	}
	if _, err := os.Stat(dst); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("失效缓存未清理: %v", err)
	}
}

func TestStoredCoverageUsesOnlyExactRepresentation(t *testing.T) {
	g := bareGenerator(t, int(SizeSmall))
	const mtime = "2026-10-25T00:00:00.000Z"
	version := g.RepresentationVersion(42, mtime)
	writeTestFile(t, g.thumbDst("asset", SizeGrid, version), "cache")
	if !g.HasStoredThumbnail("asset", SizeGrid, 42, mtime) {
		t.Fatal("未命中元数据表示")
	}
	if g.HasStoredThumbnail("asset", SizeGrid, 43, mtime) {
		t.Fatal("旧版本被计入覆盖")
	}
	if g.HasStoredThumbnail("asset", SizePreview, 42, mtime) {
		t.Fatal("其他档被计入覆盖")
	}
	if g.HasStoredThumbnail("other", SizeGrid, 42, mtime) {
		t.Fatal("孤儿被计入覆盖")
	}
}
