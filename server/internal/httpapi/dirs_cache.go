// dirs_cache.go：GET /dirs 目录树进程内短 TTL 缓存（审计 R3，2026-09-20）。
// 树的构建 = 每请求全量递归遍历整库磁盘（无上界）；文件整理选择器每次
// 打开都付一遍。缓存按 libraryId 存整棵树，TTL 内复用。不做截断（目录
// 显示不全是行为变化，已拍板排除）；TTL 上界 = 服务端之外（SMB 直改磁盘）
// 新建目录的最长不可见窗口。树构建后只读（指针字段不再变），并发序列化
// 安全；并发未命中各自重建、后者覆盖，无害。
package httpapi

import (
	"sync"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
)

// dirsCacheTTL 树缓存有效期（调度参数具名常量，AI_README 代码卫生 5）。
const dirsCacheTTL = 10 * time.Second

type dirsCacheEntry struct {
	tree     gen.DirTree
	deadline time.Time
}

// dirsCache 按 libraryId 的树缓存；now 注入服务端时钟便于测试推进。
type dirsCache struct {
	mu  sync.Mutex
	m   map[string]dirsCacheEntry
	now func() time.Time
}

func newDirsCache(now func() time.Time) *dirsCache {
	return &dirsCache{m: make(map[string]dirsCacheEntry), now: now}
}

// get 命中且未过期返回 (tree, true)。
func (c *dirsCache) get(libraryID string) (gen.DirTree, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[libraryID]
	if !ok || !c.now().Before(e.deadline) {
		return gen.DirTree{}, false
	}
	return e.tree, true
}

func (c *dirsCache) put(libraryID string, tree gen.DirTree) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.m[libraryID] = dirsCacheEntry{tree: tree, deadline: c.now().Add(dirsCacheTTL)}
}

// invalidate 服务端自身建目录后即时失效（upload/move 的 MkdirAll 不失效：
// 低频且选择器常态关闭，≤TTL 自愈——三处接线不值得，见文件头）。
func (c *dirsCache) invalidate(libraryID string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.m, libraryID)
}
