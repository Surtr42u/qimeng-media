package authorattach

// 本地 txt 自动镜像测试：未配置跳过 / 正常原子写（逐字节比对）/ 指名片段
// 定位 / 目标不可达不 panic 只 Warn / 无残留临时文件；两代布局（当前/上次
// 目录约定）的推导、轮换、拒覆盖与旧 flat3 回退。

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
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

func TestMasterMirrorProtectsUnimportedEdits(t *testing.T) {
	path := filepath.Join(t.TempDir(), "master.txt")
	if err := writeMasterFile(path, "initial", nil, nil); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("manual"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := writeMasterFile(path, "updated", nil, nil); err == nil {
		t.Fatal("unimported manual edit was overwritten")
	}
	if got, _ := readMirror(t, path); got != "manual" {
		t.Fatal("manual contents changed")
	}
	if err := writeMasterFile(path, "updated", []Source{{Content: "manual"}}, nil); err != nil {
		t.Fatal(err)
	}
	if got, _ := readMirror(t, path); got != "updated" {
		t.Fatal("accepted import did not resume projection")
	}
}

func TestMasterMirrorKeepsThreeDistinctGenerations(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "master.txt")
	for _, content := range []string{"one", "two", "three", "three", "four"} {
		if err := writeMasterFile(path, content, nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	for name, expected := range map[string]string{
		"master.txt": "four", "master（上次）.txt": "three", "master（上上次）.txt": "two",
	} {
		if got, _ := readMirror(t, filepath.Join(dir, name)); got != expected {
			t.Fatalf("%s = %q, want %q", name, got, expected)
		}
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 6 {
		t.Fatalf("want three files and their checksums, got %d: %v", len(entries), err)
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

// ---- 两代分代布局（2026-11-07 R7）：当前/上次目录约定 ----

// writeMasterForTest 写主表镜像并在失败时终止用例。
func writeMasterForTest(t *testing.T, path, content string) {
	t.Helper()
	if err := writeMasterFile(path, content, nil, nil); err != nil {
		t.Fatalf("写主表镜像 %s 失败: %v", path, err)
	}
}

// assertMirrorChecksum 断言摘要文件存在且与正文复算一致（每代独立摘要）。
func assertMirrorChecksum(t *testing.T, path string) {
	t.Helper()
	body, ok := readMirror(t, path)
	if !ok {
		t.Fatalf("镜像正文不存在: %s", path)
	}
	sum, ok := readMirror(t, path+".sha256")
	if !ok {
		t.Fatalf("镜像摘要不存在: %s", path)
	}
	want := fmt.Sprintf("%x", sha256.Sum256([]byte(body)))
	if strings.TrimSpace(sum) != want {
		t.Fatalf("镜像摘要与正文不符: %s", path)
	}
}

// twoGenerationPaths 返回合成维护根下的当前/上次主表路径（不创建目录）。
func twoGenerationPaths(t *testing.T) (current, previous string) {
	t.Helper()
	root := filepath.Join(t.TempDir(), "资料维护")
	return filepath.Join(root, "当前", "作者资料", "作者总表.txt"),
		filepath.Join(root, "上次", "作者资料", "作者总表.txt")
}

// mkdirFor 建文件所在目录（镜像写入不自动创建当前代目录，与既有语义一致）。
func mkdirFor(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
}

func TestDerivePreviousGenerationPath(t *testing.T) {
	root := filepath.Join(t.TempDir(), "资料维护")
	asciiRoot := filepath.Join(t.TempDir(), "maintenance")
	// 盘符/共享根本身：代目录直接建在根下时按越界拒绝（跨根写点不推导）。
	volumeRoot := filepath.VolumeName(t.TempDir()) + string(filepath.Separator)
	cases := []struct {
		name string
		path string
		want string
		ok   bool
	}{
		{"中文代目录", filepath.Join(root, "当前", "作者资料", "作者总表.txt"),
			filepath.Join(root, "上次", "作者资料", "作者总表.txt"), true},
		{"ASCII代目录", filepath.Join(asciiRoot, "current", "authors", "master.txt"),
			filepath.Join(asciiRoot, "previous", "authors", "master.txt"), true},
		{"无代目录回退flat3", filepath.Join(root, "作者资料", "作者总表.txt"), "", false},
		{"文件名含当前不算代目录", filepath.Join(root, "当前作者总表.txt"), "", false},
		{"多段代目录不猜", filepath.Join(root, "当前", "当前", "x.txt"), "", false},
		{"相对路径不推导", filepath.Join("当前", "x.txt"), "", false},
		{"代目录在盘符根属越界", filepath.Join(volumeRoot, "当前", "x.txt"), "", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := derivePreviousGenerationPath(tc.path)
			if ok != tc.ok {
				t.Fatalf("推导 %q ok=%v, want %v", tc.path, ok, tc.ok)
			}
			if ok && got != tc.want {
				t.Fatalf("推导 %q = %q, want %q", tc.path, got, tc.want)
			}
		})
	}
}

// 内容变化：先把当前代（正文+摘要）提交为上次代，再写新当前代；相同数据
// 重导出只刷新当前代、不挤掉上次代。
func TestMasterMirrorTwoGenerationRotation(t *testing.T) {
	current, previous := twoGenerationPaths(t)
	mkdirFor(t, current)
	writeMasterForTest(t, current, "one")
	if _, ok := readMirror(t, previous); ok {
		t.Fatal("首次写入不应产生上次代")
	}
	writeMasterForTest(t, current, "two")
	if got, _ := readMirror(t, previous); got != "one" {
		t.Fatalf("上次代=%q, want one", got)
	}
	writeMasterForTest(t, current, "three")
	if got, _ := readMirror(t, current); got != "three" {
		t.Fatalf("当前代=%q, want three", got)
	}
	if got, _ := readMirror(t, previous); got != "two" {
		t.Fatalf("上次代=%q, want two", got)
	}
	assertMirrorChecksum(t, current)
	assertMirrorChecksum(t, previous)
	// 相同数据重导出只刷新当前代。
	writeMasterForTest(t, current, "three")
	if got, _ := readMirror(t, previous); got != "two" {
		t.Fatalf("相同数据后上次代=%q, want two", got)
	}
	// 当前/上次各自独立目录：配置目录内不再生成旧 flat3 文件，目录内只有
	// 正文与摘要两个文件。
	if _, ok := readMirror(t, filepath.Join(filepath.Dir(current), "作者总表（上次）.txt")); ok {
		t.Fatal("两代布局不应再写同目录旧三代文件")
	}
	for _, dir := range []string{filepath.Dir(current), filepath.Dir(previous)} {
		entries, err := os.ReadDir(dir)
		if err != nil || len(entries) != 2 {
			t.Fatalf("目录 %s 应只有正文与摘要: %v", dir, err)
		}
		assertNoTempLeft(t, dir)
	}
}

// 上次代来历不明（缺摘要/摘要不符/不可读）时拒绝覆盖并保留现场，绝不删除
// 或覆盖非本工具写出的文件。
func TestMasterMirrorTwoGenerationRefusesUnknownPrevious(t *testing.T) {
	t.Run("缺摘要", func(t *testing.T) {
		current, previous := twoGenerationPaths(t)
		mkdirFor(t, current)
		mkdirFor(t, previous)
		writeMasterForTest(t, current, "one")
		if err := os.WriteFile(previous, []byte("intruder"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := writeMasterFile(current, "two", nil, nil); err == nil {
			t.Fatal("上次代缺摘要时不应覆盖")
		}
		if got, _ := readMirror(t, current); got != "one" {
			t.Fatalf("拒写后当前代被改动: %q", got)
		}
		if got, _ := readMirror(t, previous); got != "intruder" {
			t.Fatalf("拒写后上次代被覆盖: %q", got)
		}
	})
	t.Run("摘要不符", func(t *testing.T) {
		current, previous := twoGenerationPaths(t)
		mkdirFor(t, current)
		mkdirFor(t, previous)
		writeMasterForTest(t, current, "one")
		if err := os.WriteFile(previous, []byte("intruder"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(previous+".sha256", []byte(strings.Repeat("0", 64)), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := writeMasterFile(current, "two", nil, nil); err == nil {
			t.Fatal("上次代摘要不符时不应覆盖")
		}
		if got, _ := readMirror(t, previous); got != "intruder" {
			t.Fatalf("拒写后上次代被覆盖: %q", got)
		}
	})
	t.Run("不可读", func(t *testing.T) {
		current, previous := twoGenerationPaths(t)
		mkdirFor(t, current)
		writeMasterForTest(t, current, "one")
		// 上次代路径被目录占用：读取失败，按不可读拒绝覆盖。
		if err := os.MkdirAll(previous, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := writeMasterFile(current, "two", nil, nil); err == nil {
			t.Fatal("上次代不可读时不应继续轮换")
		}
		if got, _ := readMirror(t, current); got != "one" {
			t.Fatalf("拒写后当前代被改动: %q", got)
		}
	})
}

// 外部编辑保护在两代布局下同样生效：当前代摘要不符则拒写；导入该内容后
// 恢复投影，并按两代语义把导入前版本轮换为上次代。
func TestMasterMirrorTwoGenerationProtectsExternalEdit(t *testing.T) {
	current, previous := twoGenerationPaths(t)
	mkdirFor(t, current)
	writeMasterForTest(t, current, "one")
	if err := os.WriteFile(current, []byte("manual"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := writeMasterFile(current, "two", nil, nil); err == nil {
		t.Fatal("未导入的外部编辑不应被覆盖")
	}
	if got, _ := readMirror(t, current); got != "manual" {
		t.Fatalf("拒写后当前代被改动: %q", got)
	}
	if _, ok := readMirror(t, previous); ok {
		t.Fatal("拒写时不应产生上次代")
	}
	if err := writeMasterFile(current, "two", []Source{{Content: "manual"}}, nil); err != nil {
		t.Fatalf("导入后恢复投影失败: %v", err)
	}
	if got, _ := readMirror(t, previous); got != "manual" {
		t.Fatalf("上次代=%q, want manual", got)
	}
	if got, _ := readMirror(t, current); got != "two" {
		t.Fatalf("当前代=%q, want two", got)
	}
}

// 轮换中途失败的重试：上次代正文已提交但摘要未写（或新当前代未写）时，
// 重试补写摘要并完成轮换，不重复轮换同一份旧内容、不丢上一代。
func TestMasterMirrorTwoGenerationRetryAfterPartialCommit(t *testing.T) {
	current, previous := twoGenerationPaths(t)
	mkdirFor(t, current)
	mkdirFor(t, previous)
	writeMasterForTest(t, current, "one")
	// 模拟「上次代正文已写、摘要未写」的中断现场：正文等于待提交的当前代。
	if err := writeFileAtomically(previous, "one"); err != nil {
		t.Fatalf("预置上次代正文失败: %v", err)
	}
	if err := writeMasterFile(current, "two", nil, nil); err != nil {
		t.Fatalf("重试轮换失败: %v", err)
	}
	if got, _ := readMirror(t, previous); got != "one" {
		t.Fatalf("上次代=%q, want one", got)
	}
	assertMirrorChecksum(t, previous)
	if got, _ := readMirror(t, current); got != "two" {
		t.Fatalf("当前代=%q, want two", got)
	}
	assertMirrorChecksum(t, current)
	assertNoTempLeft(t, filepath.Dir(previous))
}

// Refresh 全链路（master 模式）：配置在 …/当前/… 时，内容变化把上一份
// 提交到 …/上次/…，相同数据重复刷新不轮换。
func TestMirrorWriterTwoGenerationRefresh(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	current, previous := twoGenerationPaths(t)
	mkdirFor(t, current)
	if err := SaveMirrorConfig(ctx, q, testNow, MirrorConfig{Path: current, Mode: "master"}); err != nil {
		t.Fatalf("写镜像配置失败: %v", err)
	}
	if err := PersistSources(ctx, q, testNow, []Source{
		{Filename: "a.txt", Content: "1  aaa\n", ImportedAt: store.FormatTimestamp(testNow)},
	}); err != nil {
		t.Fatalf("预置片段失败: %v", err)
	}
	// master 模式只投影库里已有的常规作者行：先建对应作者，主表才有内容。
	if _, err := (&Service{}).ResolveOrCreateAuthors(ctx, q, []string{"aaa"}, testNow); err != nil {
		t.Fatalf("预置作者失败: %v", err)
	}
	w := &MirrorWriter{}
	w.Refresh(ctx, q)
	firstMaster, ok := readMirror(t, current)
	if !ok {
		t.Fatal("首次刷新未产生当前代")
	}
	if _, ok := readMirror(t, previous); ok {
		t.Fatal("首次刷新不应产生上次代")
	}
	later := testNow.Add(time.Hour)
	if err := PersistSources(ctx, q, later, []Source{
		{Filename: "b.txt", Content: "1  bbb\n", ImportedAt: store.FormatTimestamp(later)},
	}); err != nil {
		t.Fatalf("更新片段失败: %v", err)
	}
	if _, err := (&Service{}).ResolveOrCreateAuthors(ctx, q, []string{"bbb"}, later); err != nil {
		t.Fatalf("新增作者失败: %v", err)
	}
	w.Refresh(ctx, q)
	secondMaster, _ := readMirror(t, current)
	if secondMaster == firstMaster {
		t.Fatal("作者内容变化后当前代应更新")
	}
	if got, _ := readMirror(t, previous); got != firstMaster {
		t.Fatalf("上次代=%q, want 上一份当前代", got)
	}
	assertMirrorChecksum(t, current)
	assertMirrorChecksum(t, previous)
	w.Refresh(ctx, q)
	if got, _ := readMirror(t, previous); got != firstMaster {
		t.Fatalf("相同数据刷新后上次代=%q, want 不变", got)
	}
	assertNoTempLeft(t, filepath.Dir(current))
	assertNoTempLeft(t, filepath.Dir(previous))
}
