package recommendfeed

import (
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
	ids []string
	at  time.Time
}

// Cache 使用既有 singleflight 与 mutex；无后台清扫线程或额外依赖。
// 若未来采用共享缓存基础设施可整体替换；预算不足只放弃缓存，不改变候选。
type Cache struct {
	mu         sync.Mutex
	entries    map[Key]entry
	count      int
	generation uint64
	sf         singleflight.Group
}

func NewCache() *Cache { return &Cache{entries: make(map[Key]entry)} }

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
	e, ok := c.entries[key]
	return e.ids, ok && time.Since(e.at) < TTL
}

// order 的结果只读；singleflight 内再次检查缓存，避免刚完成时重复冷算。
func (c *Cache) order(key Key, compute func() ([]string, error)) ([]string, error) {
	if ids, ok := c.get(key); ok {
		return ids, nil
	}
	c.mu.Lock()
	generation := c.generation
	c.mu.Unlock()
	sfKey := fmt.Sprintf("%d|%d|%q|%d|%q|%t|%q", generation, key.Revision, key.Day, key.Seed, key.MediaType, key.CosOnly, key.Prefs)
	value, err, _ := c.sf.Do(sfKey, func() (any, error) {
		if ids, ok := c.get(key); ok {
			return ids, nil
		}
		ids, err := safeCompute(compute)
		if err != nil {
			return nil, err
		}
		c.mu.Lock()
		defer c.mu.Unlock()
		if generation != c.generation || len(ids) > maxCachedIDs {
			return ids, nil
		}
		for k, e := range c.entries {
			if time.Since(e.at) >= TTL {
				c.count -= len(e.ids)
				delete(c.entries, k)
			}
		}
		if len(c.entries) >= maxEntries || c.count+len(ids) > maxCachedIDs {
			c.entries = make(map[Key]entry)
			c.count = 0
		}
		c.entries[key] = entry{ids: ids, at: time.Now()}
		c.count += len(ids)
		return ids, nil
	})
	if err != nil {
		return nil, err
	}
	return value.([]string), nil
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
