package thumbnail

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// TestWorkerPoolAllTasksComplete 并发提交 100 个任务必须全部完成；
// 其中部分任务返回错误也不许中断池（坏文件不拖垮整批）。
func TestWorkerPoolAllTasksComplete(t *testing.T) {
	var count atomic.Int64
	pool := NewWorkerPool(context.Background(), 4, 128, func(_ context.Context, task Task) error {
		if task.AssetID == "bad" {
			return errors.New("模拟坏文件") // 必须被记录且被跳过，不影响其他任务
		}
		count.Add(1)
		return nil
	}, nil)

	for i := 0; i < 100; i++ {
		id := "ok"
		if i == 50 {
			id = "bad"
		}
		if err := pool.Submit(Task{AssetID: id}); err != nil {
			t.Fatalf("提交任务 %d 失败: %v", i, err)
		}
	}
	pool.Close()

	if got := count.Load(); got != 99 {
		t.Fatalf("期望 99 个正常任务全部完成（1 个坏任务被跳过），实际完成 %d", got)
	}
}

// TestWorkerPoolSubmitAfterClose 关闭后提交必须报 ErrPoolClosed，
// 且重复 Close 安全（不 panic）。
func TestWorkerPoolSubmitAfterClose(t *testing.T) {
	pool := NewWorkerPool(context.Background(), 1, 8, func(context.Context, Task) error {
		return nil
	}, nil)
	pool.Close()
	pool.Close() // 重复关闭必须安全

	if err := pool.Submit(Task{}); !errors.Is(err, ErrPoolClosed) {
		t.Fatalf("关闭后提交应返回 ErrPoolClosed，得到 %v", err)
	}
}

// TestWorkerPoolQueueFullDrops 队列满时 Submit 必须立即丢弃（ErrQueueFull）
// 且不阻塞：单 worker 卡住 + 容量 1 的队列下，第 3 个任务被丢弃、前两个最终完成。
func TestWorkerPoolQueueFullDrops(t *testing.T) {
	started := make(chan struct{})
	gate := make(chan struct{})
	var once sync.Once
	var done atomic.Int64

	pool := NewWorkerPool(context.Background(), 1, 1, func(_ context.Context, _ Task) error {
		once.Do(func() { close(started) }) // 通知测试：worker 已开始处理第 1 个任务
		<-gate                              // 人为卡住 worker，制造队列满条件
		done.Add(1)
		return nil
	}, nil)

	if err := pool.Submit(Task{AssetID: "1"}); err != nil {
		t.Fatalf("提交第 1 个任务失败: %v", err)
	}
	<-started // 等 worker 真正取走第 1 个（此刻队列已空），消除时序竞态

	if err := pool.Submit(Task{AssetID: "2"}); err != nil {
		t.Fatalf("提交第 2 个任务失败: %v", err) // 进入队列（容量 1）
	}

	begin := time.Now()
	if err := pool.Submit(Task{AssetID: "3"}); !errors.Is(err, ErrQueueFull) {
		t.Fatalf("队列满应丢弃并返回 ErrQueueFull，得到 %v", err)
	}
	if elapsed := time.Since(begin); elapsed > time.Second {
		t.Fatalf("Submit 被阻塞了 %v，违背非阻塞红线", elapsed)
	}

	close(gate) // 放行 worker
	pool.Close()
	if got := done.Load(); got != 2 {
		t.Fatalf("期望 1、2 号任务完成、3 号被丢弃（完成 2 个），实际完成 %d", got)
	}
}
