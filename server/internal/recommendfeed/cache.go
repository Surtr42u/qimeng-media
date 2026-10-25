package recommendfeed

import (
	"context"
	"fmt"
	"log/slog"
	"runtime/debug"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"
)

const (
	// TTL 约束顺序快照；过期后每日惩罚重新参与评分。
	TTL        = time.Minute
	maxEntries = 32
	// 仅保留 ID；总预算约束大库/频繁换 seed 的常驻内存，不截断候选。
	maxCachedIDs = 100000
)

// Key 不含分页窗口，使相同评分环境的连续页共享一次计算。
type Key struct {
	Revision  int64
	Day       string
	Seed      int
	MediaType string
	CosOnly   bool
	Prefs     string
}

type entry struct {
	ids  []string
	at   time.Time
	used uint64 // 仅控制淘汰，不续期快照 TTL。
}

// Cache 使用既有 singleflight 与 mutex；无后台清扫线程或额外依赖。
// 若未来采用共享缓存基础设施可整体替换；预算不足只放弃缓存，不改变候选。
type Cache struct {
	mu          sync.Mutex
	entries     map[Key]entry
	count       int
	generation  uint64
	useSequence uint64
	life        context.Context
	cancel      context.CancelFunc
	closed      bool
	builds      map[string]*sharedOrder
	changed     chan struct{}
	maxBuilds   int
	workers     sync.WaitGroup
	sf          singleflight.Group
}

func NewCache() *Cache {
	c := &Cache{entries: make(map[Key]entry)}
	c.initLifecycle()
	return c
}

// Clear 与迟到的计算登记互斥，防止失效后旧计算重新占用预算。
func (c *Cache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries = make(map[Key]entry)
	c.count = 0
	c.generation++
}

func (c *Cache) get(key Key) ([]string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil, false
	}
	e, ok := c.entries[key]
	if !ok {
		return nil, false
	}
	if time.Since(e.at) >= TTL {
		c.count -= len(e.ids)
		delete(c.entries, key)
		return nil, false
	}
	c.useSequence++
	e.used = c.useSequence
	c.entries[key] = e
	return e.ids, true
}

// order 的结果只读；singleflight 内再次检查缓存，避免刚完成时重复冷算。
func (c *Cache) order(key Key, compute func() ([]string, error)) ([]string, error) {
	if ids, ok := c.get(key); ok {
		return ids, nil
	}
	return c.orderInGeneration(key, c.currentGeneration(), compute)
}

func (c *Cache) currentGeneration() uint64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.generation
}

func orderFlightKey(generation uint64, key Key) string {
	return fmt.Sprintf("%d|%d|%q|%d|%q|%t|%q", generation, key.Revision, key.Day, key.Seed, key.MediaType, key.CosOnly, key.Prefs)
}

// 固定本轮计算所属代次；失效仅阻止发布，已经共享该计算的前台仍能取得结果。
func (c *Cache) orderInGeneration(key Key, generation uint64, compute func() ([]string, error)) ([]string, error) {
	return c.orderContext(context.Background(), key, generation, func(context.Context) ([]string, error) { return compute() })
}

func (c *Cache) publishOrder(key Key, generation uint64, ids []string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || generation != c.generation || len(ids) > maxCachedIDs {
		return
	}
	for k, e := range c.entries {
		if time.Since(e.at) >= TTL {
			c.count -= len(e.ids)
			delete(c.entries, k)
		}
	}
	if previous, ok := c.entries[key]; ok {
		c.count -= len(previous.ids)
		delete(c.entries, key)
	}
	// 最多 32 项，线性选冷项比另维护链表更简单；仅淘汰满足预算所需的项。
	for len(c.entries) >= maxEntries || c.count+len(ids) > maxCachedIDs {
		var cold Key
		var oldest uint64
		found := false
		for k, e := range c.entries {
			if !found || e.used < oldest {
				cold, oldest, found = k, e.used, true
			}
		}
		c.count -= len(c.entries[cold].ids)
		delete(c.entries, cold)
	}
	c.useSequence++
	c.entries[key] = entry{ids: ids, at: time.Now(), used: c.useSequence}
	c.count += len(ids)
}

func safeCompute(compute func() ([]string, error)) (ids []string, err error) {
	defer func() {
		if p := recover(); p != nil {
			ids, err = nil, fmt.Errorf("推荐顺序计算 panic: %v", p)
			slog.Error("推荐顺序计算 panic", "panic", p, "stack", string(debug.Stack()))
		}
	}()
	return compute()
}
