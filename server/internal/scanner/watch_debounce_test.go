package scanner

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"testing/synctest"
	"time"

	"github.com/fsnotify/fsnotify"

	"qimeng-media/server/internal/store/db"
)

// 用标准库虚拟时钟固定事件间隔；实际文件系统的调度延迟不能作为合批次数的依据。
// 此处只驱动真实 receive，不引入测试时钟接口或改变生产扫描逻辑。
func TestWatchDebounceCoalesces(t *testing.T) {
	root := t.TempDir()
	synctest.Test(t, func(t *testing.T) {
		const debounce = 50 * time.Millisecond
		fw := &fsnotify.Watcher{Events: make(chan fsnotify.Event), Errors: make(chan error)}
		w := watcher{s: &Scanner{watchDebounce: debounce}, lib: db.Library{RootPath: root}, fw: fw}
		ctx, cancel := context.WithCancel(context.Background())
		batches := make(chan watchBatch)
		done := make(chan error, 1)
		go func() { done <- w.receive(ctx, batches) }()
		defer func() {
			cancel()
			synctest.Wait()
			if err := <-done; !errors.Is(err, context.Canceled) {
				t.Errorf("取消接收器: %v", err)
			}
		}()

		// 消费注册后的初始对账；后续断言只针对指定的同路径 Write 风暴。
		if initial := <-batches; !initial.full {
			t.Fatal("初始批次必须完整对账")
		}
		path := filepath.Join(root, "v.mp4")
		for i := 0; i < 3; i++ {
			fw.Events <- fsnotify.Event{Name: path, Op: fsnotify.Write}
			synctest.Wait()
			if i < 2 {
				time.Sleep(40 * time.Millisecond)
			}
		}
		// 总事件跨度80ms超过首个窗口，必须由最后一次事件重新计时。
		time.Sleep(debounce - time.Nanosecond)
		synctest.Wait()
		select {
		case <-batches:
			t.Fatal("最后一次事件静默满窗口前不能发布批次")
		default:
		}
		time.Sleep(time.Nanosecond)
		synctest.Wait()
		select {
		case batch := <-batches:
			if _, ok := batch.paths[path]; batch.full || len(batch.paths) != 1 || !ok {
				t.Fatalf("同路径事件须合并为一个路径: %+v", batch)
			}
		default:
			t.Fatal("静默满窗口必须发布合并批次")
		}
		time.Sleep(2 * debounce)
		synctest.Wait()
		select {
		case <-batches:
			t.Fatal("同一事件风暴不能重复发布批次")
		default:
		}
	})
}
