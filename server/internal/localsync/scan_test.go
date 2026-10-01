// scan_test.go：同步根扫描剪枝规则与分类、根重叠判定的锁定测试。
package localsync

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// lsWrite 在 dir 下写一个文件（父目录自动创建），返回完整路径。
func lsWrite(t *testing.T, dir, rel, content string) string {
	t.Helper()
	p := filepath.Join(dir, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatalf("创建目录失败: %v", err)
	}
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}
	return p
}

// lsEntryPaths 收集 Entry 切片的 RelPath 排序结果（断言用）。
func lsEntryPaths(entries []Entry) map[string]Entry {
	m := make(map[string]Entry, len(entries))
	for _, e := range entries {
		m[e.RelPath] = e
	}
	return m
}

// TestClassifyRelPath 三分类：媒体扩展名、根级 txt、库文件夹内 txt（other）、
// 其余扩展名（other）。大小写不敏感。
func TestClassifyRelPath(t *testing.T) {
	cases := []struct {
		rel  string
		want FileKind
	}{
		{"a.jpg", KindMedia},
		{"lib/sub/clip.MP4", KindMedia},
		{"lib/clip.m4v", KindMedia},
		{"作者清单.txt", KindTxt},
		{"lib/inner.txt", KindOther}, // 库文件夹内 txt 不是导入通道
		{"lib/inner.pdf", KindOther},
		{"note.pdf", KindOther},
		{"noext", KindOther},
	}
	for _, c := range cases {
		if got := ClassifyRelPath(c.rel); got != c.want {
			t.Fatalf("ClassifyRelPath(%q) = %q，期望 %q", c.rel, got, c.want)
		}
	}
}

// TestScanTreePruneRules 全量剪枝规则：.synced 整棵静默、顶层点条目 ignored、
// 嵌套点条目静默、符号链接顶层 ignored/嵌套静默、普通文件全收。
func TestScanTreePruneRules(t *testing.T) {
	root := t.TempDir()
	lsWrite(t, root, "lib1/a.jpg", "jpg")
	lsWrite(t, root, "lib1/sub/b.mp4", "mp4")
	lsWrite(t, root, "作者清单.txt", "txt")
	lsWrite(t, root, "lib1/inner.txt", "nope") // 库内 txt：other 分类，仍进 entries
	lsWrite(t, root, ".synced/archived.txt", "hidden")
	lsWrite(t, root, ".synced/nested/c.bin", "hidden")
	lsWrite(t, root, ".trash/keep.txt", "hidden-dir")
	lsWrite(t, root, "lib1/.DS_Store", "nested-dot-file")
	lsWrite(t, root, "lib1/.hidden/c.mp4", "nested-dot-dir")
	// 顶层符号链接（目录）与嵌套符号链接：Windows 创建符号链接需要特权，
	// CI/开发机常见无特权环境——用文件符号链接并在不可创建时跳过该断言段。
	topLink := filepath.Join(root, "top-link")
	nestedLink := filepath.Join(root, filepath.FromSlash("lib1/nested-link"))
	linkErr := os.Symlink(lsWrite(t, root, "link-target.txt", "t"), topLink)
	if linkErr != nil {
		t.Skipf("本机无法创建符号链接，跳过符号链接断言段: %v", linkErr)
	}
	if err := os.Symlink(filepath.Join(root, "lib1", "a.jpg"), nestedLink); err != nil {
		t.Fatalf("创建嵌套符号链接失败: %v", err)
	}

	entries, ignored, err := ScanTree(root)
	if err != nil {
		t.Fatalf("ScanTree 失败: %v", err)
	}
	got := lsEntryPaths(entries)
	// 普通文件（含库内 txt——分类是 other 但它仍是可见条目）。
	for _, want := range []string{"lib1/a.jpg", "lib1/sub/b.mp4", "作者清单.txt", "lib1/inner.txt"} {
		if _, ok := got[want]; !ok {
			t.Fatalf("普通文件 %s 未进 entries：%v", want, got)
		}
	}
	// .synced 与嵌套点条目完全不可见（entries 与 ignored 都不出）。
	for _, invisible := range []string{".synced/archived.txt", ".synced/nested/c.bin", "lib1/.DS_Store", "lib1/.hidden/c.mp4", "lib1/nested-link"} {
		if _, ok := got[invisible]; ok {
			t.Fatalf("静默条目 %s 不应进 entries", invisible)
		}
		if _, ok := lsEntryPaths(ignored)[invisible]; ok {
			t.Fatalf("静默条目 %s 不应进 ignored", invisible)
		}
	}
	ign := lsEntryPaths(ignored)
	for _, want := range []string{".trash", "top-link"} {
		if _, ok := ign[want]; !ok {
			t.Fatalf("顶层点目录/顶层符号链接 %s 应进 ignored：%v", want, ign)
		}
	}
	// 顶层符号链接标记 IsSymlink；普通文件不标。
	if e := ign["top-link"]; !e.IsSymlink {
		t.Fatal("顶层符号链接应标记 IsSymlink")
	}
	if e := got["作者清单.txt"]; e.IsSymlink || e.Size != int64(len("txt")) {
		t.Fatalf("普通文件 Entry 元数据错误：%+v", e)
	}
	// 词法序确定性：两次扫描结果顺序一致。
	entries2, _, err := ScanTree(root)
	if err != nil {
		t.Fatalf("二次 ScanTree 失败: %v", err)
	}
	for i := range entries {
		if entries[i].RelPath != entries2[i].RelPath {
			t.Fatalf("词法序不稳定：%s != %s", entries[i].RelPath, entries2[i].RelPath)
		}
	}
}

// TestScanTreePreservesModTime mtime 观测值与文件系统一致（稳定性判定输入）。
func TestScanTreePreservesModTime(t *testing.T) {
	root := t.TempDir()
	p := lsWrite(t, root, "a.jpg", "x")
	old := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	if err := os.Chtimes(p, old, old); err != nil {
		t.Fatalf("Chtimes 失败: %v", err)
	}
	entries, _, err := ScanTree(root)
	if err != nil {
		t.Fatalf("ScanTree 失败: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("期望 1 个条目，得到 %d", len(entries))
	}
	if !entries[0].ModTime.Equal(old) {
		t.Fatalf("mtime 观测值失真：%v != %v", entries[0].ModTime, old)
	}
}

// TestScanTreeMissingRoot 根不存在 → err 非空（通道级错误路径）。
func TestScanTreeMissingRoot(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "不存在")
	if _, _, err := ScanTree(missing); err == nil {
		t.Fatal("根不存在应返回错误")
	}
}

// TestRootOverlapError 内含/被含/相等判重叠，无关不判；dataDir 空跳过。
func TestRootOverlapError(t *testing.T) {
	base := t.TempDir()
	libRoot := filepath.Join(base, "lib")
	dataDir := filepath.Join(base, "data")
	syncRoot := filepath.Join(base, "sync")
	wideRoot := filepath.Join(base, "wide") // 同步根形态，内部藏一个库根
	for _, d := range []string{libRoot, filepath.Join(libRoot, "inner"), dataDir, syncRoot,
		filepath.Join(dataDir, "sub"), wideRoot, filepath.Join(wideRoot, "lib")} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatalf("建目录失败: %v", err)
		}
	}
	cases := []struct {
		name        string
		root        string
		dataDir     string
		libRoots    []string
		wantOverlap bool
	}{
		{"无关", syncRoot, dataDir, []string{libRoot}, false},
		{"同步根在库根内", filepath.Join(libRoot, "inner"), dataDir, []string{libRoot}, true},
		{"库根在同步根内", wideRoot, dataDir, []string{filepath.Join(wideRoot, "lib")}, true},
		{"相等", libRoot, dataDir, []string{libRoot}, true},
		{"同步根含库根", base, dataDir, []string{libRoot}, true},
		{"与数据目录重叠", filepath.Join(dataDir, "sub"), dataDir, nil, true},
		{"数据目录为空跳过", syncRoot, "", []string{libRoot}, false},
		{"库根为空跳过", syncRoot, dataDir, []string{""}, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := RootOverlapError(c.root, c.dataDir, c.libRoots)
			if c.wantOverlap && got == "" {
				t.Fatalf("期望判重叠，得到空串（root=%s）", c.root)
			}
			if !c.wantOverlap && got != "" {
				t.Fatalf("期望无重叠，得到 %q", got)
			}
		})
	}
}
