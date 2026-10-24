package thumbnail

import (
	"context"
	"runtime"
	"sync"
)

// genSlots 是进程级 FFmpeg 预算，容量仍跟随 GOMAXPROCS。
// 多核后台新任务只用到容量减一，保留前台余量；单核允许一件后台任务，
// 已等待前台优先取得下一空位。正在生成的任务不抢占，最长受 frameTimeout 约束。
var genSlots = newSemaphore(runtime.GOMAXPROCS(0))

var foregroundPriority = func() <-chan struct{} {
	ch := make(chan struct{})
	close(ch)
	return ch
}()

// semaphore 用 mutex + 状态广播实现可取消、可提升的优先准入。
// 标准 channel/x/sync semaphore 没有同任务动态提升及前台保留预算语义；
// 只维护计数，不建立无界任务队列。若将来采用统一优先任务执行器可整体退役。
type semaphore struct {
	mu                sync.Mutex
	capacity          int
	running           int
	foregroundWaiting int
	changed           chan struct{}
}

func newSemaphore(capacity int) *semaphore {
	if capacity <= 0 {
		capacity = 1
	}
	return &semaphore{capacity: capacity, changed: make(chan struct{})}
}

func (s *semaphore) acquire(ctx context.Context) bool {
	return s.acquirePriority(ctx, foregroundPriority)
}

// priority 关闭即为前台；后台在等待时可由同表示的前台请求关闭它来提升。
// 提升只影响下一次准入，不中断已经开始的生成，也不增加并发总量。
func (s *semaphore) acquirePriority(ctx context.Context, priority <-chan struct{}) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	foreground := false
	defer func() {
		if foreground {
			s.foregroundWaiting--
			s.signalLocked()
		}
	}()
	for {
		if ctx.Err() != nil {
			return false
		}
		if !foreground {
			select {
			case <-priority:
				foreground = true
				s.foregroundWaiting++
				priority = nil
			default:
			}
		}
		limit := s.capacity
		if !foreground && limit > 1 {
			limit--
		}
		if s.running < limit && (foreground || s.foregroundWaiting == 0) {
			s.running++
			return true
		}
		changed := s.changed
		s.mu.Unlock()
		select {
		case <-ctx.Done():
		case <-changed:
		case <-priority:
		}
		s.mu.Lock()
	}
}

func (s *semaphore) signalLocked() {
	close(s.changed)
	s.changed = make(chan struct{})
}

func (s *semaphore) release() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.running--
	s.signalLocked()
}
