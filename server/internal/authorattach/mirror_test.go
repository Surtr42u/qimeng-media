package authorattach

// 本地 txt 自动镜像测试：未配置跳过 / 正常原子写（逐字节比对）/ 指名片段
// 定位 / 目标不可达不 panic 只 Warn / 无残留临时文件。

import (
	"context"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"testing"
	"time"

	"qimeng-media/server/internal/store"
)

// readMirror 读镜像文件内容（不存在 → 带标记的错误，供「跳过」断言）。
func readMirror(t *testing.T, path string) (string, bool) {
	t.Helper()
	b, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return "", false
	}
	if err != nil {
		t.Fatalf("读镜像 %s 失败: %v", path, err)
	}
	return string(b), true
}

// assertNoTempLeft 断言目录内无 .qm-mirror-* 临时文件残留。
func assertNoTempLeft(t *testing.T, dir string) {
	t.Helper()
	left, err := filepath.Glob(filepath.Join(dir, mirrorTempPrefix+"*"))
	if err != nil {
		t.Fatalf("扫描临时文件失败: %v", err)
	}
	if len(left) != 0 {
		t.Errorf("临时文件残留: %v", left)
	}
}

func TestMirrorWriterDisabledWithoutConfig(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "f.txt", Content: "1  aaa\n", ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	dir := t.TempDir()
	// 无配置记录 = path 空 = 关闭：Refresh 不写任何文件、不 panic。
	(&MirrorWriter{}).Refresh(ctx, q)
	if _, ok := readMirror(t, filepath.Join(dir, "mirror.txt")); ok {
		t.Error("未配置时不应产生镜像文件")
	}
}

func TestMirrorWriterRefresh(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	older := store.FormatTimestamp(testNow)
	newer := store.FormatTimestamp(testNow.Add(time.Hour))
	recent := "1  bbb\n来源\nsite-a\n作品\nb.png\n"
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "a.txt", Content: "1  aaa\n", ImportedAt: older},
		{Filename: "b.txt", Content: recent, ImportedAt: newer},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	dir := t.TempDir()
	w := &MirrorWriter{}

	// 默认镜像对象 = 最近导入片段，内容逐字节一致（REQ 验收 #14）。
	path := filepath.Join(dir, "authors.txt")
	if err := SaveMirrorConfig(ctx, q, testNow, MirrorConfig{Path: path}); err != nil {
		t.Fatalf("写镜像配置失败: %v", err)
	}
	w.Refresh(ctx, q)
	if got, ok := readMirror(t, path); !ok || got != recent {
		t.Errorf("镜像内容=%q ok=%v, want %q", got, ok, recent)
	}
	assertNoTempLeft(t, dir)

	// 重复刷新覆盖既有文件（rename 覆盖路径，Windows 同样可用）。
	w.Refresh(ctx, q)
	if got, _ := readMirror(t, path); got != recent {
		t.Errorf("覆盖刷新后内容=%q, want %q", got, recent)
	}

	// 配置指名片段：镜像该片段而非最近导入。
	named := filepath.Join(dir, "named.txt")
	if err := SaveMirrorConfig(ctx, q, testNow, MirrorConfig{Path: named, FragmentFilename: "a.txt"}); err != nil {
		t.Fatalf("写镜像配置失败: %v", err)
	}
	w.Refresh(ctx, q)
	if got, ok := readMirror(t, named); !ok || got != "1  aaa\n" {
		t.Errorf("指名镜像=%q ok=%v, want 1  aaa\\n", got, ok)
	}

	// 指名不存在的片段：跳过，不产文件。
	ghost := filepath.Join(dir, "ghost.txt")
	if err := SaveMirrorConfig(ctx, q, testNow, MirrorConfig{Path: ghost, FragmentFilename: "无.txt"}); err != nil {
		t.Fatalf("写镜像配置失败: %v", err)
	}
	w.Refresh(ctx, q)
	if _, ok := readMirror(t, ghost); ok {
		t.Error("目标片段不存在时不应产生镜像文件")
	}
	assertNoTempLeft(t, dir)
}

// 目标目录不可写（不存在）：只 Warn 不 panic、不向上抛错，核心流程不受
// 影响（REQ 验收 #15）。
func TestMirrorWriterUnwritablePath(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "f.txt", Content: "1  aaa\n", ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	bad := filepath.Join(t.TempDir(), "no-such-dir", "mirror.txt")
	if err := SaveMirrorConfig(ctx, q, testNow, MirrorConfig{Path: bad}); err != nil {
		t.Fatalf("写镜像配置失败: %v", err)
	}
	// 走到这里且不 panic 即通过（Warn 由 slog 输出，无返回值可断言）。
	(&MirrorWriter{}).Refresh(ctx, q)
}

// 镜像恢复补写（REQ §3.4「路径恢复后的下一次变更自动补写」）：path 指向
// 不存在目录 → Refresh 失败仅 Warn → 目录恢复后再 Refresh → 文件写出且
// 内容与片段逐字一致。
func TestMirrorWriterRecoversAfterDirCreated(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	content := "1  aaa\n来源\nsite-a\n作品\na.png\n"
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "f.txt", Content: content, ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	dir := filepath.Join(t.TempDir(), "later")
	path := filepath.Join(dir, "authors.txt")
	if err := SaveMirrorConfig(ctx, q, testNow, MirrorConfig{Path: path}); err != nil {
		t.Fatalf("写镜像配置失败: %v", err)
	}
	w := &MirrorWriter{}

	// 目录不存在：失败仅 Warn，不写出文件、不 panic。
	w.Refresh(ctx, q)
	if _, ok := readMirror(t, path); ok {
		t.Fatal("目录不存在时不应写出镜像文件")
	}

	// 目录恢复后的下一次变更时机（此处直接再 Refresh 模拟）：补写成功。
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	w.Refresh(ctx, q)
	if got, ok := readMirror(t, path); !ok || got != content {
		t.Fatalf("恢复后镜像=%q ok=%v, want %q", got, ok, content)
	}
	assertNoTempLeft(t, dir)
}
