package httpapi

import (
	"qimeng-media/server/internal/recommendfeed"
	"sync"
)

// recommendCache 保持既有结构性失效入口；只缓存顺序 ID，响应与签名现取。
type recommendCache struct {
	mu    sync.Mutex
	rev   int64
	order *recommendfeed.Cache
}

func newRecommendCache() *recommendCache { return &recommendCache{order: recommendfeed.NewCache()} }
func (c *recommendCache) invalidate() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.rev++
	c.order.Clear()
}
func (c *recommendCache) revision() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.rev
}
func (s *Server) invalidateRecommendCache() {
	if s.recommendCache != nil {
		s.recommendCache.invalidate()
	}
}
