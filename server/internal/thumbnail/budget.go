package thumbnail

import (
	"context"
	"sync"

	"qimeng-media/server/internal/sysmon"
)

// representationBudget 只计最终路径的不同表示，队列/集合续生/实际生成共用引用。
// 复用既有 256 队列规模，不把外层集合和内层尺寸各算一次工作量。
// 标准 semaphore 不能对重叠键去重；若改用统一表示调度器应整件退役。
type representationBudget struct {
	mu      sync.Mutex
	refs    map[string]int
	limit   int
	changed chan struct{}
	closed  bool
}

type representationLease struct {
	budget *representationBudget
	keys   []string
	once   sync.Once
}

func newRepresentationBudget(limit int) *representationBudget {
	return &representationBudget{refs: make(map[string]int), limit: limit, changed: make(chan struct{})}
}

func (b *representationBudget) acquire(ctx context.Context, keys []string, wait bool) (*representationLease, error) {
	unique := make(map[string]struct{}, len(keys))
	for _, key := range keys {
		unique[key] = struct{}{}
	}
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		b.mu.Lock()
		if b.closed {
			b.mu.Unlock()
			return nil, ErrPoolClosed
		}
		missing := 0
		for key := range unique {
			if b.refs[key] == 0 {
				missing++
			}
		}
		if len(b.refs)+missing <= b.limit {
			lease := &representationLease{budget: b, keys: make([]string, 0, len(unique))}
			for key := range unique {
				b.refs[key]++
				lease.keys = append(lease.keys, key)
			}
			sysmon.Default.AddThumbWork("admitted", missing)
			b.mu.Unlock()
			return lease, nil
		}
		changed := b.changed
		b.mu.Unlock()
		if !wait || len(unique) > b.limit {
			sysmon.Default.IncThumbWork("rejected")
			return nil, ErrQueueFull
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-changed:
		}
	}
}

func (l *representationLease) release() {
	if l == nil {
		return
	}
	l.once.Do(func() {
		b := l.budget
		b.mu.Lock()
		defer b.mu.Unlock()
		removed := 0
		for _, key := range l.keys {
			b.refs[key]--
			if b.refs[key] == 0 {
				delete(b.refs, key)
				removed++
			}
		}
		sysmon.Default.AddThumbWork("admitted", -removed)
		close(b.changed)
		b.changed = make(chan struct{})
	})
}

func (b *representationBudget) close() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.closed {
		b.closed = true
		close(b.changed)
		b.changed = make(chan struct{})
	}
}
