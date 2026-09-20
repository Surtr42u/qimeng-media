// dirs_cache_test.go：目录树 TTL 缓存纯单元测试（审计 R3，2026-09-20）。
// fake clock 注入推进时间，不碰磁盘；覆盖命中/过期/失效/跨库隔离四象限。
package httpapi

import (
	"testing"
	"time"
)

func newTestDirsCache() (*dirsCache, *time.Time) {
	base := time.Unix(0, 0).UTC()
	cur := base
	c := newDirsCache(func() time.Time { return cur })
	return c, &cur
}

func TestDirsCacheHitWithinTTL(t *testing.T) {
	c, cur := newTestDirsCache()
	tree := buildDirTree("/tmp/nonexistent-root", "")
	c.put("lib1", tree)
	got, ok := c.get("lib1")
	if !ok {
		t.Fatal("TTL 内应命中")
	}
	if got.Path == nil || *got.Path != "" {
		t.Fatalf("命中的树内容应与 put 的一致, got %+v", got)
	}
	// 推进 9s（< 10s TTL）仍命中
	*cur = cur.Add(9 * time.Second)
	if _, ok := c.get("lib1"); !ok {
		t.Fatal("推进 9s 后仍应命中（TTL=10s）")
	}
}

func TestDirsCacheExpiresAfterTTL(t *testing.T) {
	c, cur := newTestDirsCache()
	c.put("lib1", buildDirTree("/tmp/nonexistent-root", ""))
	// 推进 11s（> 10s TTL）后必须 miss
	*cur = cur.Add(11 * time.Second)
	if _, ok := c.get("lib1"); ok {
		t.Fatal("推进 11s 后应过期 miss")
	}
}

func TestDirsCacheInvalidate(t *testing.T) {
	c, _ := newTestDirsCache()
	c.put("lib1", buildDirTree("/tmp/nonexistent-root", ""))
	c.invalidate("lib1")
	if _, ok := c.get("lib1"); ok {
		t.Fatal("invalidate 后应 miss")
	}
	// 重复 invalidate 幂等无害
	c.invalidate("lib1")
}

func TestDirsCachePerLibraryIsolation(t *testing.T) {
	c, _ := newTestDirsCache()
	c.put("lib1", buildDirTree("/tmp/nonexistent-root-a", ""))
	c.put("lib2", buildDirTree("/tmp/nonexistent-root-b", ""))
	c.invalidate("lib1")
	if _, ok := c.get("lib1"); ok {
		t.Fatal("lib1 失效后应 miss")
	}
	if _, ok := c.get("lib2"); !ok {
		t.Fatal("lib1 失效不应影响 lib2")
	}
}
