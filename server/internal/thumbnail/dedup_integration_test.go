package thumbnail

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestPoolAndForegroundInvokeFFmpegOnceForOverlappingSizes(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("计数包装使用 POSIX shell；Windows 另跑无外部进程的闸位/续生回归")
	}
	requireFFmpeg(t)
	dir := t.TempDir()
	src := makeTallPNG(t, dir)
	gen := NewGenerator(filepath.Join(dir, "cache"), nil, Options{Workers: 1, LongSide: int(SizeSmall)})
	defer gen.Close()
	realBin, err := exec.LookPath(DefaultFFmpegBin)
	if err != nil {
		t.Fatal(err)
	}
	count, gate := filepath.Join(dir, "calls"), filepath.Join(dir, "gate")
	t.Setenv("QIMENG_TEST_THUMB_REAL", realBin)
	t.Setenv("QIMENG_TEST_THUMB_CALLS", count)
	t.Setenv("QIMENG_TEST_THUMB_GATE", gate)
	wrapper := filepath.Join(dir, "ffmpeg-counter")
	if err := os.WriteFile(wrapper, []byte("#!/bin/sh\nprintf 'run\\n' >> \"$QIMENG_TEST_THUMB_CALLS\"\nwhile [ ! -f \"$QIMENG_TEST_THUMB_GATE\" ]; do sleep 0.01; done\nexec \"$QIMENG_TEST_THUMB_REAL\" \"$@\"\n"), 0o700); err != nil {
		t.Fatal(err)
	}
	gen.ffmpegBin = wrapper
	if err := gen.Submit(Task{AssetID: "asset", SourcePath: src, Kind: KindImage, Sizes: []Size{SizeGrid}}); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for {
		if data, err := os.ReadFile(count); err == nil && len(data) > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("后台生成未启动")
		}
		time.Sleep(time.Millisecond)
	}
	// 生成体处于真实外部进程内，前台不同顺序、重叠尺寸请求都应加入同一任务。
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Go(func() {
			if err := gen.EnsureDetached(context.Background(), "asset", src, KindImage, []Size{SizeSmall, SizeGrid}); err != nil {
				t.Error(err)
			}
		})
	}
	// 门闩释放后，即使较晚的请求刚进入，也只能命中已生成缓存。
	if err := os.WriteFile(gate, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	wg.Wait()
	gen.Close()
	data, err := os.ReadFile(count)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(string(data), "run\n") != 1 {
		t.Fatalf("重复执行 ffmpeg: %q", data)
	}
	if !gen.HasThumbnail("asset", SizeGrid, src) {
		t.Fatal("共用缓存未落盘")
	}
}
