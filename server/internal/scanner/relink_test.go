package scanner

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// relink_test.go 库根自动重挂（ADR-0025）的测试：纯函数匹配判定的表驱动
// 用例 + 经真实 SQLite 与临时目录的端到端用例（Scan / Watch 两条入口、
// 唯一命中/多候选/零候选/父目录丢失/空库各分支）。

// ---------- 测试基础设施 ----------

// newRelinkEnv 与 newTestEnv 同构（真实 SQLite + 静音日志扫描器），但库根
// 建在自建 parent 下一层——重挂测试需要控制"旧根父目录"的生灭与兄弟目录
// 布局，newTestEnv 把库根直接放在 t.TempDir() 上做不到。
func newRelinkEnv(t *testing.T, dirName string) (*testEnv, string) {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "relink.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移测试库失败: %v", err)
	}
	q := db.New(conn)

	base := t.TempDir()                   // 测试基座（cleanup 覆盖整棵树）
	parent := filepath.Join(base, "pool") // 旧根父目录
	root := filepath.Join(parent, dirName)
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatalf("建库根失败: %v", err)
	}
	lib, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID:        uuid.NewString(),
		Name:      dirName,
		RootPath:  root,
		Kind:      LibraryKindNormal,
		CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("创建测试库记录失败: %v", err)
	}

	bus := events.NewBus(nil, 128)
	t.Cleanup(bus.Close)
	s := New(q, bus, slog.New(slog.NewTextHandler(io.Discard, nil)), "", nil)
	return &testEnv{s: s, q: q, bus: bus, lib: lib, conn: conn}, parent
}

// writeExt 在库外任意绝对路径写入指定字节的文件（自动建父目录），
// 构造诱饵/迁走目标用。
func writeExt(t *testing.T, abs string, size int) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	if err := os.WriteFile(abs, bytes.Repeat([]byte("x"), size), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}
}

// getLibRow 读取库行当前状态。
func getLibRow(t *testing.T, e *testEnv) db.Library {
	t.Helper()
	lib, err := e.q.GetLibrary(context.Background(), e.lib.ID)
	if err != nil {
		t.Fatalf("GetLibrary 失败: %v", err)
	}
	return lib
}

// ---------- 纯函数：候选匹配与唯一性门槛 ----------

func TestPickRelinkCandidate(t *testing.T) {
	samples := []relinkSample{
		{RelPath: "a.jpg", SizeBytes: 100},
		{RelPath: "sub/b.jpg", SizeBytes: 200},
	}
	fullMatch := map[string]relinkProbe{
		"a.jpg":     {Found: true, SizeBytes: 100},
		"sub/b.jpg": {Found: true, SizeBytes: 200},
	}

	cases := []struct {
		name       string
		samples    []relinkSample
		candidates []relinkCandidate
		wantBest   string
		wantQual   int
	}{
		{
			name:    "唯一命中",
			samples: samples,
			candidates: []relinkCandidate{
				{Name: "decoy", Probes: map[string]relinkProbe{
					"a.jpg": {Found: true, SizeBytes: 999}, // 大小不符
				}},
				{Name: "renamed-lib", Probes: fullMatch},
			},
			wantBest: "renamed-lib", wantQual: 1,
		},
		{
			name:    "多候选（两个目录结构完全一致）",
			samples: samples,
			candidates: []relinkCandidate{
				{Name: "renamed-lib", Probes: fullMatch},
				{Name: "backup-copy", Probes: fullMatch},
			},
			wantBest: "renamed-lib", wantQual: 2, // best 无意义，调用方必须放弃
		},
		{
			name:    "零候选（无人命中）",
			samples: samples,
			candidates: []relinkCandidate{
				{Name: "unrelated", Probes: map[string]relinkProbe{
					"a.jpg": {Found: true, SizeBytes: 100},
					// sub/b.jpg 探测缺失
				}},
			},
			wantQual: 0,
		},
		{
			name:       "零候选（没有候选目录）",
			samples:    samples,
			candidates: nil,
			wantQual:   0,
		},
		{
			name:    "样本缺失（空库无法证同，全部不命中）",
			samples: nil,
			candidates: []relinkCandidate{
				{Name: "renamed-lib", Probes: map[string]relinkProbe{}},
			},
			wantQual: 0,
		},
		{
			name:    "大小不符（同名但内容不同）",
			samples: samples,
			candidates: []relinkCandidate{
				{Name: "renamed-lib", Probes: map[string]relinkProbe{
					"a.jpg":     {Found: true, SizeBytes: 100},
					"sub/b.jpg": {Found: true, SizeBytes: 201},
				}},
			},
			wantQual: 0,
		},
		{
			name:    "探测结果缺条目（probes 键缺失视为不命中）",
			samples: samples,
			candidates: []relinkCandidate{
				{Name: "renamed-lib", Probes: map[string]relinkProbe{
					"a.jpg": {Found: true, SizeBytes: 100},
					// sub/b.jpg 键缺失
				}},
			},
			wantQual: 0,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			best, qual := pickRelinkCandidate(tc.samples, tc.candidates)
			if qual != tc.wantQual {
				t.Fatalf("qualified = %d, want %d", qual, tc.wantQual)
			}
			if tc.wantQual == 1 && best != tc.wantBest {
				t.Fatalf("best = %q, want %q", best, tc.wantBest)
			}
		})
	}
}

// ---------- 端到端：Scan 入口 ----------

// 根目录被改名后重扫：自动重挂到新目录，资产身份零扰动（size+mtime 全
// 命中 → 无增删改移），显示名跟随新目录名，并广播 library.changed。
func TestScanAutoRelinkAfterRootRename(t *testing.T) {
	e, parent := newRelinkEnv(t, "old-name")
	e.writeFile(t, "a.jpg", 100)
	e.writeFile(t, "sub/b.jpg", 200)
	if _, err := e.s.Scan(context.Background(), e.lib); err != nil {
		t.Fatalf("首次扫描失败: %v", err)
	}

	sub := drainTopic(t, e.bus, events.TopicLibraryChanged)
	newRoot := filepath.Join(parent, "renamed-lib")
	if err := os.Rename(e.lib.RootPath, newRoot); err != nil {
		t.Fatalf("改名库根失败: %v", err)
	}

	res, err := e.s.Scan(context.Background(), e.lib) // 传旧库记录（生产轮询的常态）
	if err != nil {
		t.Fatalf("重挂后扫描失败: %v", err)
	}
	if res.Added != 0 || res.Updated != 0 || res.Moved != 0 || res.Removed != 0 {
		t.Fatalf("重挂扫描应零扰动，got +%d ~%d >%d -%d",
			res.Added, res.Updated, res.Moved, res.Removed)
	}
	if res.Scanned != 2 {
		t.Fatalf("rescanned = %d, want 2", res.Scanned)
	}

	lib := getLibRow(t, e)
	if lib.RootPath != newRoot {
		t.Fatalf("root_path = %q, want %q", lib.RootPath, newRoot)
	}
	if lib.Name != "renamed-lib" {
		t.Fatalf("display name = %q, want renamed-lib（显示名跟随目录名）", lib.Name)
	}
	if n := e.assetCount(t); n != 2 {
		t.Fatalf("asset count = %d, want 2（身份保留）", n)
	}
	if _, err := e.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{
		LibraryID: e.lib.ID, RelPath: "sub/b.jpg",
	}); err != nil {
		t.Fatalf("旧 rel_path 记录应原样保留: %v", err)
	}

	evs := collect(sub)
	if len(evs) != 1 || evs[0].Topic != events.TopicLibraryChanged {
		t.Fatalf("应恰好广播一次 library.changed，got %+v", evs)
	}
	if r, ok := evs[0].Payload.(ScanResult); !ok || r.LibraryID != e.lib.ID {
		t.Fatalf("重挂事件载荷不符: %+v", evs[0].Payload)
	}
}

// 父目录下存在两个指纹相同的候选（改名后的真身 + 结构一致的诱饵副本）：
// 合格候选 ≥2，必须放弃重挂并保持原失败行为，库行不动。
func TestScanRelinkRejectedWhenAmbiguous(t *testing.T) {
	e, parent := newRelinkEnv(t, "old-name")
	e.writeFile(t, "a.jpg", 100)
	e.writeFile(t, "sub/b.jpg", 200)
	if _, err := e.s.Scan(context.Background(), e.lib); err != nil {
		t.Fatalf("首次扫描失败: %v", err)
	}

	// 诱饵：同结构同尺寸的兄弟目录
	writeExt(t, filepath.Join(parent, "backup-copy", "a.jpg"), 100)
	writeExt(t, filepath.Join(parent, "backup-copy", "sub", "b.jpg"), 200)
	newRoot := filepath.Join(parent, "renamed-lib")
	if err := os.Rename(e.lib.RootPath, newRoot); err != nil {
		t.Fatalf("改名库根失败: %v", err)
	}

	_, err := e.s.Scan(context.Background(), e.lib)
	if err == nil {
		t.Fatal("多候选歧义必须失败，不能猜测重挂")
	}
	lib := getLibRow(t, e)
	if lib.RootPath != e.lib.RootPath || lib.Name != "old-name" {
		t.Fatalf("库行不得被改动，got root=%q name=%q", lib.RootPath, lib.Name)
	}
}

// 根目录被整体移出旧父目录：父目录可枚举但无合格候选（零命中），放弃。
func TestScanRelinkRejectedWhenNoQualifiedCandidate(t *testing.T) {
	e, parent := newRelinkEnv(t, "old-name")
	e.writeFile(t, "a.jpg", 100)
	if _, err := e.s.Scan(context.Background(), e.lib); err != nil {
		t.Fatalf("首次扫描失败: %v", err)
	}

	elsewhere := filepath.Join(filepath.Dir(parent), "elsewhere")
	if err := os.MkdirAll(elsewhere, 0o755); err != nil {
		t.Fatalf("建迁移目标失败: %v", err)
	}
	if err := os.Rename(e.lib.RootPath, filepath.Join(elsewhere, "old-name")); err != nil {
		t.Fatalf("移走库根失败: %v", err)
	}

	if _, err := e.s.Scan(context.Background(), e.lib); err == nil {
		t.Fatal("零合格候选必须失败")
	}
	if lib := getLibRow(t, e); lib.RootPath != e.lib.RootPath {
		t.Fatalf("库行不得被改动，got %q", lib.RootPath)
	}
}

// 旧父目录也不存在（整棵树被移走/盘未挂载）：直接放弃，保持原报错行为。
func TestScanRelinkRejectedWhenParentMissing(t *testing.T) {
	e, parent := newRelinkEnv(t, "old-name")
	e.writeFile(t, "a.jpg", 100)
	if _, err := e.s.Scan(context.Background(), e.lib); err != nil {
		t.Fatalf("首次扫描失败: %v", err)
	}

	base := filepath.Dir(parent)
	if err := os.Rename(parent, filepath.Join(base, "pool-moved")); err != nil {
		t.Fatalf("移走父目录失败: %v", err)
	}

	if _, err := e.s.Scan(context.Background(), e.lib); err == nil {
		t.Fatal("父目录丢失必须失败")
	}
	if lib := getLibRow(t, e); lib.RootPath != e.lib.RootPath {
		t.Fatalf("库行不得被改动，got %q", lib.RootPath)
	}
}

// 从未扫描成功的库（0 资产 = 无样本）：无法证同，绝不重挂。
func TestScanRelinkRejectedForEmptyLibrary(t *testing.T) {
	e, parent := newRelinkEnv(t, "old-name")
	if err := os.Rename(e.lib.RootPath, filepath.Join(parent, "renamed-lib")); err != nil {
		t.Fatalf("改名库根失败: %v", err)
	}

	if _, err := e.s.Scan(context.Background(), e.lib); err == nil {
		t.Fatal("无样本必须失败")
	}
	if lib := getLibRow(t, e); lib.RootPath != e.lib.RootPath {
		t.Fatalf("库行不得被改动，got %q", lib.RootPath)
	}
}

// ---------- 端到端：Watch 入口 ----------

// Watch 启动时根不在：先重挂再注册监听（重挂同步发生于 Watch 返回前），
// 取消后干净收尾（nil = addTree 在新根上成功）。
func TestWatchAutoRelinkOnRootRename(t *testing.T) {
	e, parent := newRelinkEnv(t, "old-name")
	e.writeFile(t, "a.jpg", 100)
	if _, err := e.s.Scan(context.Background(), e.lib); err != nil {
		t.Fatalf("首次扫描失败: %v", err)
	}

	sub := drainTopic(t, e.bus, events.TopicLibraryChanged)
	newRoot := filepath.Join(parent, "renamed-lib")
	if err := os.Rename(e.lib.RootPath, newRoot); err != nil {
		t.Fatalf("改名库根失败: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	errCh := make(chan error, 1)
	go func() { errCh <- e.s.Watch(ctx, e.lib) }()

	eventually(t, 3*time.Second, "Watch 启动期重挂未发生", func() bool {
		return getLibRow(t, e).RootPath == newRoot
	})
	if len(collect(sub)) == 0 {
		t.Fatal("Watch 重挂应广播 library.changed")
	}
	cancel()
	if err := <-errCh; err != nil {
		t.Fatalf("Watch 应干净收尾（新根注册成功），got: %v", err)
	}
}
