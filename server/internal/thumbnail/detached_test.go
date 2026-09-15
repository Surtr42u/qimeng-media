package thumbnail

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// detachedGroup 行为锁定（2026-09-15 批，懒生成「单飞+后台续生」）：等待者取消
// 不杀生成/同 key 合并执行一次/不同 key 各自执行/完成后的后来者直接取结果。
// fn 用可控阻塞的手工函数，不碰 ffmpeg（真生成由 ffmpeg_integration_test 另测）。

func TestDetachedGroup等待者取消不杀生成(t *testing.T) {
	dg := newDetachedGroup()
	var runs atomic.Int32
	started := make(chan struct{})
	release := make(chan struct{})

	key := "a|1"
	callerCtx, cancel := context.WithCancel(context.Background())
	go func() {
		// 该调用方不等结果：发起后立刻取消——生成必须继续跑完
		_ = dg.do(key, callerCtx, func() error {
			// do 内部 fn 挂后台 context，本函数收不到调用方取消
			runs.Add(1)
			close(started)
			<-release
			return nil
		})
	}()

	<-started
	cancel()
	// 给取消路径一点时间传播；fn 仍在 release 前运行 = 未被取消杀掉
	time.Sleep(20 * time.Millisecond)
	if runs.Load() != 1 {
		t.Fatalf("fn 应已开始执行，runs=%d", runs.Load())
	}
	close(release)

	// 生成继续后，后来者（全新 ctx）应取到已完成结果且 fn 不再重跑
	if err := dg.do(key, context.Background(), func() error {
		runs.Add(1)
		return nil
	}); err != nil {
		t.Fatalf("后来者应直接命中已完成结果: %v", err)
	}
	if got := runs.Load(); got != 1 {
		t.Fatalf("fn 总执行次数应为 1（单飞），实得 %d", got)
	}
}

func TestDetachedGroup同key并发合并为一次执行(t *testing.T) {
	dg := newDetachedGroup()
	var runs atomic.Int32
	gate := make(chan struct{})
	var wg sync.WaitGroup
	errs := make([]error, 8)

	// 先占住 key 的进行中调用：fn 阻塞在 gate
	blocked := make(chan struct{})
	go func() {
		_ = dg.do("k", context.Background(), func() error {
			runs.Add(1)
			close(blocked)
			<-gate
			return nil
		})
	}()
	<-blocked

	// 8 个并发等待者应全部合并到同一次执行：必须先于首调用完成注册——
	// 等待者全部就位后再放行 gate（单飞是「进行中合并」语义，完成即忘却，
	// 迟到者走重新执行——真链路里由 EnsureDetached 的缓存快路径兜住不重跑）
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			errs[i] = dg.do("k", context.Background(), func() error {
				runs.Add(1)
				return nil
			})
		}(i)
	}
	time.Sleep(50 * time.Millisecond) // 等 8 个等待者注册进 terms
	close(gate)
	wg.Wait()

	for i, err := range errs {
		if err != nil {
			t.Fatalf("等待者 %d 不应失败: %v", i, err)
		}
	}
	if got := runs.Load(); got != 1 {
		t.Fatalf("fn 应只执行 1 次（单飞合并），实得 %d", got)
	}
}

func TestDetachedGroup不同key各自执行(t *testing.T) {
	dg := newDetachedGroup()
	var a, b atomic.Int32
	done := make(chan struct{}, 2)
	go func() { _ = dg.do("x", context.Background(), func() error { a.Add(1); done <- struct{}{}; return nil }) }()
	go func() { _ = dg.do("y", context.Background(), func() error { b.Add(1); done <- struct{}{}; return nil }) }()
	<-done
	<-done
	if a.Load() != 1 || b.Load() != 1 {
		t.Fatalf("不同 key 应各自执行一次，x=%d y=%d", a.Load(), b.Load())
	}
}

func TestDetachedGroup错误随完成传播给后来者(t *testing.T) {
	dg := newDetachedGroup()
	boom := errors.New("boom")
	if err := dg.do("e", context.Background(), func() error { return boom }); err == nil {
		t.Fatal("首个调用方应拿到 fn 的错误")
	}
	if err := dg.do("e", context.Background(), func() error { return nil }); err != nil {
		t.Fatalf("完成后的后来者应取到已存结果（错误缓存语义同单飞惯例）: %v", err)
	}
}
