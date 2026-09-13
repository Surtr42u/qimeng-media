package thumbnail

import (
	"context"
	"sync"
	"testing"
	"time"
)

// TestSemaphoreCapsConcurrentHolders 锁定并发闸的核心语义：N 个并发请求者在容量 C
// 的闸上竞争，同时持有闸位者的峰值不得超过 C（超过=限流失效，ffmpeg 风暴回归）。
func TestSemaphoreCapsConcurrentHolders(t *testing.T) {
	const (
		capacity   = 2
		goroutines = 8
		holdTime   = 20 * time.Millisecond // 持闸窗口：制造排队，否则可能全程串行不构成并发压力
	)
	s := newSemaphore(capacity)
	var (
		mu   sync.Mutex
		cur  int // 当前持闸人数（峰值观测窗口）
		peak int
		wg   sync.WaitGroup
	)
	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if !s.acquire(context.Background()) {
				t.Error("背景 ctx 不取消，acquire 不应失败")
				return
			}
			mu.Lock()
			cur++
			if cur > peak {
				peak = cur
			}
			mu.Unlock()
			time.Sleep(holdTime)
			mu.Lock()
			cur--
			mu.Unlock()
			s.release()
		}()
	}
	wg.Wait()
	if peak > capacity {
		t.Fatalf("并发持闸峰值 = %d，超过容量 %d（限流失效）", peak, capacity)
	}
	// 全部归还后闸应回到空：下一次 acquire 立即成功（不泄漏闸位）
	if !s.acquire(context.Background()) {
		t.Fatal("全部 release 后 acquire 应立即可用")
	}
	s.release()
}

// TestSemaphoreAcquireInterruptedByContext 锁定取消语义：闸满时排队者必须能被已取消
// 的 ctx 立即中断（对应懒生成路径上游 r.Context() 断开——客户端已走，排队者不许傻等），
// 且中断不消耗闸位（不泄漏容量）。
func TestSemaphoreAcquireInterruptedByContext(t *testing.T) {
	s := newSemaphore(1)
	if !s.acquire(context.Background()) {
		t.Fatal("空闸 acquire 应立即成功")
	}
	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	done := make(chan bool, 1)
	go func() { done <- s.acquire(canceled) }()
	select {
	case ok := <-done:
		if ok {
			s.release()
			t.Fatal("ctx 已取消时 acquire 不应成功（排队必须可被取消中断）")
		}
	case <-time.After(time.Second):
		t.Fatal("已取消 ctx 的排队等待未被中断（阻塞超过 1s）")
	}
	// 取消的排队者不得消耗闸位：归还后容量应完整可用
	s.release()
	if !s.acquire(context.Background()) {
		t.Fatal("取消中断后闸位被泄漏：acquire 应立即可用")
	}
	s.release()
}

// TestNewSemaphoreClampsCapacity 锁定容量夹紧：<=0 的容量必须被夹到 1（否则无缓冲
// channel 语义漂移成会合，闸位计数失效），保证坏配置退化为「串行」而非「失控」。
func TestNewSemaphoreClampsCapacity(t *testing.T) {
	s := newSemaphore(0)
	if !s.acquire(context.Background()) {
		t.Fatal("容量 0 被夹紧后 acquire 应立即成功")
	}
	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	if s.acquire(canceled) {
		s.release()
		t.Fatal("夹紧后的容量应为 1：第二个 acquire 不应成功")
	}
	s.release()
}
