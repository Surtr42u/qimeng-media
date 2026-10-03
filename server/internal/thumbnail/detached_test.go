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
// 不杀生成/同 key 合并执行一次/不同 key 各自执行/完成条目回收（迟到者重新发起，
// 生产链路由 EnsureDetached 的磁盘缓存快路径兜底不重跑 ffmpeg——2026-10-03 审查批
// 勘正：单飞只覆盖「进行中」，与 x/sync/singleflight 同款语义）。
// fn 用可控阻塞的手工函数，不碰 ffmpeg（真生成由 ffmpeg_integration_test 另测）。

// waitTermsRecycled 等 key 的完成条目被后台回收（fn done → delete(terms) 两步间
// 存在窗口，本函数收敛该窗口，让后续断言不依赖「完成通知 vs 回收清理」的时序）。
func waitTermsRecycled(t *testing.T, dg *detachedGroup, key string) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for {
		dg.mu.Lock()
		gone := dg.terms[key] == nil
		dg.mu.Unlock()
		if gone {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("后台 goroutine 未在 1s 内回收完成条目")
		}
		time.Sleep(time.Millisecond)
	}
}

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

	// 生成完成后条目即被回收，同 key 再来 = 全新一次生成（进行中合并语义，
	// 与同 key 合并测试口径一致）。先等回收完成再断言——旧断言「后来者直接
	// 取结果」只在 do 抢到 delete 之前的锁时成立，另一半时序 fn 重跑被误判
	// 失败（2026-10-03 CI -race 实撞，审查批修复）。
	waitTermsRecycled(t, dg, key)
	if err := dg.do(key, context.Background(), func() error {
		runs.Add(1)
		return nil
	}); err != nil {
		t.Fatalf("回收后来者重新发起应成功: %v", err)
	}
	if got := runs.Load(); got != 2 {
		t.Fatalf("回收后来者应重新执行 fn（runs=2），实得 %d", got)
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
	go func() {
		_ = dg.do("x", context.Background(), func() error { a.Add(1); done <- struct{}{}; return nil })
	}()
	go func() {
		_ = dg.do("y", context.Background(), func() error { b.Add(1); done <- struct{}{}; return nil })
	}()
	<-done
	<-done
	if a.Load() != 1 || b.Load() != 1 {
		t.Fatalf("不同 key 应各自执行一次，x=%d y=%d", a.Load(), b.Load())
	}
}

func TestDetachedGroup错误传播与回收后迟到者重跑(t *testing.T) {
	dg := newDetachedGroup()
	boom := errors.New("boom")
	if err := dg.do("e", context.Background(), func() error { return boom }); err == nil {
		t.Fatal("首个调用方应拿到 fn 的错误")
	}
	// 完成条目回收后，迟到者是全新调用——拿到本次 fn 的结果而非旧错误缓存
	// （旧断言「后来者取到已存结果」只在完成通知先于回收清理的时序成立，
	// 另一半时序重跑新 fn 返回 nil 恰好也过检，属空转断言，一并勘正）。
	waitTermsRecycled(t, dg, "e")
	var runs atomic.Int32
	if err := dg.do("e", context.Background(), func() error { runs.Add(1); return nil }); err != nil {
		t.Fatalf("回收后迟到者应拿到本次 fn 的 nil 结果，实得 %v: %v", err, err)
	}
	if runs.Load() != 1 {
		t.Fatal("回收后迟到者应真实执行 fn")
	}
}
