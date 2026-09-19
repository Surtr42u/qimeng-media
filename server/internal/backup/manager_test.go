// manager_test.go：快照管理器单测（调度面以外的核心行为锁定）。
// 覆盖：命名白名单与时间戳格式、轮转保留（超出删最旧）、进行中防重入
// （并发触发断言只成功一次）、删除/打开的白名单与 404 路径、目录惰性创建。
package backup

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeSnapshot 返回一个把 dest 写成固定内容的快照执行器（替身不碰真库——
// VACUUM INTO 本身的真库验证在 httpapi 端到端测试）。
func fakeSnapshot(t *testing.T) SnapshotFunc {
	t.Helper()
	return func(_ context.Context, dest string) error {
		return os.WriteFile(dest, []byte("SQLite format 3 fake snapshot"), 0o644)
	}
}

// newTestManager 组装带固定时钟的管理器（起始于 2026-09-19 10:00:00 本地语义
// 的固定点，每步推进 by 参数）。
func newTestManager(t *testing.T, dir string, retention int, snapshot SnapshotFunc) (*Manager, *time.Time) {
	t.Helper()
	base := time.Date(2026, 9, 19, 10, 0, 0, 0, time.Local)
	m, err := NewManager(Options{
		Dir: dir, Snapshot: snapshot, Retention: retention,
		Now: func() time.Time { return base },
	})
	if err != nil {
		t.Fatalf("组装管理器失败: %v", err)
	}
	return m, &base
}

// TestCreateNameWhitelistAndInfo 锁定快照命名格式（本地时间 + 白名单）与
// Info 字段（大小/时刻）。
func TestCreateNameWhitelistAndInfo(t *testing.T) {
	dir := filepath.Join(t.TempDir(), DirName)
	m, _ := newTestManager(t, dir, 3, fakeSnapshot(t))

	info, err := m.Create(context.Background())
	if err != nil {
		t.Fatalf("Create 失败: %v", err)
	}
	if !nameRe.MatchString(info.Name) {
		t.Fatalf("快照名 %q 未过白名单 %s", info.Name, snapshotNamePattern)
	}
	// 本地时间语义：名字内嵌时间戳必须与固定时钟的本地形态一致。
	want := "qimeng-20260919-100000.db"
	if info.Name != want {
		t.Fatalf("快照名 = %q, 期望 %q（本地时间命名）", info.Name, want)
	}
	if info.SizeBytes <= 0 {
		t.Fatalf("SizeBytes = %d, 期望 > 0", info.SizeBytes)
	}
	if info.CreatedAt == 0 {
		t.Fatal("CreatedAt 不应为 0")
	}
	if _, err := os.Stat(filepath.Join(dir, info.Name)); err != nil {
		t.Fatalf("快照文件未落盘: %v", err)
	}
}

// TestRotateKeepsRetention 轮转：retention=3 连续建 5 份，只留最新 3 份，
// 删掉的是最旧两份（文件名字典序 = 时间序）。
func TestRotateKeepsRetention(t *testing.T) {
	dir := t.TempDir()
	base := time.Date(2026, 9, 19, 10, 0, 0, 0, time.Local)
	step := time.Second
	cur := base
	m, err := NewManager(Options{
		Dir: dir, Snapshot: fakeSnapshot(t), Retention: 3,
		Now: func() time.Time { cur = cur.Add(step); return cur },
	})
	if err != nil {
		t.Fatalf("组装管理器失败: %v", err)
	}
	created := make([]string, 0, 5)
	for i := 0; i < 5; i++ {
		info, err := m.Create(context.Background())
		if err != nil {
			t.Fatalf("第 %d 次 Create 失败: %v", i+1, err)
		}
		created = append(created, info.Name)
	}
	list, err := m.List()
	if err != nil {
		t.Fatalf("List 失败: %v", err)
	}
	if len(list) != 3 {
		t.Fatalf("轮转后剩 %d 份, 期望 3", len(list))
	}
	// 新→旧：最后创建的三份。
	wantNewestFirst := []string{created[4], created[3], created[2]}
	for i, name := range wantNewestFirst {
		if list[i].Name != name {
			t.Fatalf("列表[%d] = %q, 期望 %q（新→旧）", i, list[i].Name, name)
		}
	}
	// 最旧两份的文件必须已从磁盘消失。
	for _, gone := range created[:2] {
		if _, err := os.Stat(filepath.Join(dir, gone)); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("超龄快照 %s 应已删除, stat err = %v", gone, err)
		}
	}
}

// TestConcurrentCreateOnlyOneSucceeds 防重入：并发触发 N 次，断言恰好一次
// 成功、其余全部 ErrInProgress，且只落一个快照文件。
func TestConcurrentCreateOnlyOneSucceeds(t *testing.T) {
	dir := t.TempDir()
	// 快照执行器带真实耗时窗口，放大并发撞闸概率；用 atomic 计数校验
	// 执行器本身也只被进入一次。
	var inFlight, executed atomic.Int32
	snapshot := func(_ context.Context, dest string) error {
		if inFlight.Add(1) != 1 {
			t.Errorf("快照执行器被并发进入（防重入闸失效）")
		}
		time.Sleep(50 * time.Millisecond)
		executed.Add(1)
		inFlight.Add(-1)
		return os.WriteFile(dest, []byte("x"), 0o644)
	}
	m, _ := newTestManager(t, dir, 7, snapshot)

	const n = 8
	var wg sync.WaitGroup
	errs := make([]error, n)
	start := make(chan struct{})
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start // 全员就位后同时开跑
			_, errs[i] = m.Create(context.Background())
		}(i)
	}
	close(start)
	wg.Wait()

	success := 0
	for i, err := range errs {
		switch {
		case err == nil:
			success++
		case errors.Is(err, ErrInProgress):
			// 预期失败路径
		default:
			t.Fatalf("goroutine %d 得到意外错误: %v", i, err)
		}
	}
	if success != 1 {
		t.Fatalf("成功次数 = %d, 期望恰好 1", success)
	}
	if executed.Load() != 1 {
		t.Fatalf("快照执行器执行次数 = %d, 期望 1", executed.Load())
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("读目录失败: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("落盘快照数 = %d, 期望 1", len(entries))
	}
}

// TestDeleteAndOpenNameWhitelist 删除/打开的名字白名单：路径穿越、扩展名
// 变体、目录段全部拒绝（ErrInvalidName），绝不触达文件系统。
func TestDeleteAndOpenNameWhitelist(t *testing.T) {
	dir := t.TempDir()
	m, _ := newTestManager(t, dir, 7, fakeSnapshot(t))
	for _, name := range []string{
		"../evil.db",
		"qimeng-20260919-100000.db.bak", // 扩展名变体
		"sub/qimeng-20260919-100000.db", // 目录段
		"qimeng-2026-100000.db",         // 格式不符
		"not-a-backup.db",
		"",
	} {
		if err := m.Delete(name); !errors.Is(err, ErrInvalidName) {
			t.Fatalf("Delete(%q) err = %v, 期望 ErrInvalidName", name, err)
		}
		if _, _, err := m.OpenFile(name); !errors.Is(err, ErrInvalidName) {
			t.Fatalf("OpenFile(%q) err = %v, 期望 ErrInvalidName", name, err)
		}
	}
	// 白名单拒绝时不得在目录里产生任何副作用。
	if entries, err := os.ReadDir(dir); err == nil && len(entries) != 0 {
		t.Fatalf("白名单拒绝不应触碰目录, 发现 %d 项", len(entries))
	}
}

// TestDeleteNotFound 合法名字但快照不存在 → ErrNotFound（目录不存在同路径）。
func TestDeleteNotFound(t *testing.T) {
	m, _ := newTestManager(t, filepath.Join(t.TempDir(), DirName), 7, fakeSnapshot(t))
	if err := m.Delete("qimeng-20260919-100000.db"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Delete 未存在快照 err = %v, 期望 ErrNotFound", err)
	}
	if _, _, err := m.OpenFile("qimeng-20260919-100000.db"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("OpenFile 未存在快照 err = %v, 期望 ErrNotFound", err)
	}
}

// TestDeleteRoundTrip 建一份、删一份、列表归零的全链。
func TestDeleteRoundTrip(t *testing.T) {
	m, _ := newTestManager(t, filepath.Join(t.TempDir(), DirName), 7, fakeSnapshot(t))
	info, err := m.Create(context.Background())
	if err != nil {
		t.Fatalf("Create 失败: %v", err)
	}
	list, err := m.List()
	if err != nil || len(list) != 1 {
		t.Fatalf("Create 后列表 = %v (err %v), 期望 1 份", list, err)
	}
	if err := m.Delete(info.Name); err != nil {
		t.Fatalf("Delete 失败: %v", err)
	}
	list, err = m.List()
	if err != nil || len(list) != 0 {
		t.Fatalf("Delete 后列表 = %v (err %v), 期望 0 份", list, err)
	}
}

// TestListLazyDirectory 目录不存在时 List 返回空而非报错（惰性创建语义）。
func TestListLazyDirectory(t *testing.T) {
	m, _ := newTestManager(t, filepath.Join(t.TempDir(), DirName), 7, fakeSnapshot(t))
	list, err := m.List()
	if err != nil {
		t.Fatalf("List 目录不存在时不应报错: %v", err)
	}
	if len(list) != 0 {
		t.Fatalf("期望空列表, 得到 %v", list)
	}
}

// TestListIgnoresForeignFiles 目录里的外来文件（非快照命名）不进列表、
// 轮转不删它们（避免误伤用户放进来的东西）。
func TestListIgnoresForeignFiles(t *testing.T) {
	dir := t.TempDir()
	m, _ := newTestManager(t, dir, 7, fakeSnapshot(t))
	foreign := filepath.Join(dir, "readme.txt")
	if err := os.WriteFile(foreign, []byte("hi"), 0o644); err != nil {
		t.Fatalf("写外来文件失败: %v", err)
	}
	if _, err := m.Create(context.Background()); err != nil {
		t.Fatalf("Create 失败: %v", err)
	}
	list, err := m.List()
	if err != nil {
		t.Fatalf("List 失败: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("期望只列 1 份快照, 得到 %d", len(list))
	}
	if !strings.HasSuffix(list[0].Name, ".db") {
		t.Fatalf("列表混入非 .db 项: %q", list[0].Name)
	}
	if _, err := os.Stat(foreign); err != nil {
		t.Fatalf("外来文件不应被动过: %v", err)
	}
}

// TestNewManagerValidation 组装校验：缺目录/缺快照执行器必须显式报错。
func TestNewManagerValidation(t *testing.T) {
	if _, err := NewManager(Options{Snapshot: fakeSnapshot(t)}); err == nil {
		t.Fatal("缺 Dir 应报错")
	}
	if _, err := NewManager(Options{Dir: t.TempDir()}); err == nil {
		t.Fatal("缺 Snapshot 应报错")
	}
	// retention<=0 回落默认值。
	m, err := NewManager(Options{Dir: t.TempDir(), Snapshot: fakeSnapshot(t)})
	if err != nil {
		t.Fatalf("组装失败: %v", err)
	}
	if m.retention != DefaultBackupRetention {
		t.Fatalf("retention 回落 = %d, 期望 %d", m.retention, DefaultBackupRetention)
	}
}
