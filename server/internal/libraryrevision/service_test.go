package libraryrevision

// 修订号服务单元测试：真实 SQLite（内存临时库），锁定基线引导、原子自增、
// 缺键自愈与并发不丢增量四条契约。

import (
	"context"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// newTestService 组装真实库上的服务（临时目录 SQLite + 迁移 + 若干资产）。
func newTestService(t *testing.T, assetCount int) (*Service, *db.Queries) {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "rev.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移测试库失败: %v", err)
	}
	q := db.New(conn)
	now := store.FormatTimestamp(time.Now())
	lib, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: uuid.NewString(), Name: "测试库", RootPath: t.TempDir(),
		Kind: "normal", CreatedAt: now,
	})
	if err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	for i := 0; i < assetCount; i++ {
		// rel_path 逐资产不同：UpsertAsset 的身份冲突目标是
		// (library_id, rel_path)（ADR-0004），同名会合并成一行。
		if _, err := q.UpsertAsset(context.Background(), db.UpsertAssetParams{
			AssetID: uuid.NewString(), LibraryID: lib.ID,
			RelPath: "f" + strconv.Itoa(i) + ".jpg", FileName: "f.jpg", MediaType: "image",
			SizeBytes: 1, Mtime: now, CreatedAt: now, UpdatedAt: now,
		}); err != nil {
			t.Fatalf("入库测试资产失败: %v", err)
		}
	}
	return NewService(q, nil, nil), q
}

// TestEnsureBaselineAndGet 全新库：未引导时 Get 自愈播种（基线 =
// COUNT(assets)+1，恒 ≥1）；引导幂等，重复调用不覆盖已增值。
func TestEnsureBaselineAndGet(t *testing.T) {
	s, _ := newTestService(t, 3)
	ctx := context.Background()
	// Get 首调即自愈播种：基线 = 3 资产 + 1 = 4（不为 0，见 EnsureBaseline 注释）。
	got, err := s.Get(ctx)
	if err != nil {
		t.Fatalf("Get 失败: %v", err)
	}
	if got != 4 {
		t.Errorf("基线期望 4（COUNT(assets)+1），得到 %d", got)
	}
	// 显式 EnsureBaseline 幂等：键已存在时零行 no-op，不回退不重算。
	if err := s.EnsureBaseline(ctx); err != nil {
		t.Fatalf("EnsureBaseline 失败: %v", err)
	}
	if got, err = s.Get(ctx); err != nil || got != 4 {
		t.Errorf("重复引导后期望仍为 4，得到 %d（err=%v）", got, err)
	}
}

// TestIncrementMonotonic 自增链：每次 +1、落库持久（新服务实例读同库看到
// 同值）、缓存与库值一致。
func TestIncrementMonotonic(t *testing.T) {
	s, q := newTestService(t, 0)
	ctx := context.Background()
	prev, err := s.Get(ctx)
	if err != nil {
		t.Fatalf("Get 失败: %v", err)
	}
	for i := 0; i < 5; i++ {
		got, err := s.Increment(ctx)
		if err != nil {
			t.Fatalf("第 %d 次 Increment 失败: %v", i+1, err)
		}
		if got != prev+1 {
			t.Fatalf("自增期望 %d，得到 %d", prev+1, got)
		}
		prev = got
	}
	// 新实例（无缓存）读同一库：值已持久化，且库内存储的就是十进制文本。
	other := NewService(q, nil, nil)
	if got, err := other.Get(ctx); err != nil || got != prev {
		t.Errorf("新实例读库期望 %d，得到 %d（err=%v）", prev, got, err)
	}
}

// TestIncrementOnMissingKey 裸实例在键缺失时直接 Increment：先播种再自增
// （基线+1），不返回错误也不落回基线原值。
func TestIncrementOnMissingKey(t *testing.T) {
	s, _ := newTestService(t, 2)
	got, err := s.Increment(context.Background())
	if err != nil {
		t.Fatalf("缺键 Increment 失败: %v", err)
	}
	if want := int64(4); got != want { // 基线 2+1=3，再自增 → 4
		t.Errorf("缺键自增期望 %d（基线3+1），得到 %d", want, got)
	}
}

// TestConcurrentIncrement 并发自增不丢增量：N 个协程各自增一次，
// 最终值 = 基线 + N（进程内 mu 串行 + SQL 原子自增双层保证）。
func TestConcurrentIncrement(t *testing.T) {
	s, _ := newTestService(t, 0)
	ctx := context.Background()
	base, err := s.Get(ctx)
	if err != nil {
		t.Fatalf("Get 失败: %v", err)
	}
	const n = 32
	var wg sync.WaitGroup
	errs := make(chan error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.Increment(ctx); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatalf("并发 Increment 失败: %v", err)
	}
	if got, err := s.Get(ctx); err != nil || got != base+n {
		t.Errorf("并发自增后期望 %d，得到 %d（err=%v）", base+n, got, err)
	}
}
