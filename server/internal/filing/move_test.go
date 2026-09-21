package filing

// MoveFile 组件测试：rename 快路径与 copyFile 副本正确性。
//
// EXDEV 回落分支无法在单机单卷的单测环境里稳定复现（需要真实的两个
// 挂载点），该分支的行为由 fnOS 虚拟机 Docker 部署的端到端删除/恢复
// 链路验证（deploy/README.md 实测记录：/media 与 /data 两个卷之间
// 删除→回收站→恢复）。

import (
	"os"
	"path/filepath"
	"testing"
)

// TestMoveFileRename：同挂载点走 rename 快路径——目标有内容、源不存在。
func TestMoveFileRename(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "src.mp4")
	dst := filepath.Join(dir, "sub", "..", "dst.mp4")
	if err := os.WriteFile(src, []byte("test-content"), 0o644); err != nil {
		t.Fatalf("写源文件失败: %v", err)
	}
	if err := MoveFile(src, dst); err != nil {
		t.Fatalf("MoveFile: %v", err)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Fatalf("移动后源文件应不存在, stat err=%v", err)
	}
	b, err := os.ReadFile(dst)
	if err != nil {
		t.Fatalf("读目标文件失败: %v", err)
	}
	if string(b) != "test-content" {
		t.Fatalf("目标内容不符: %q", string(b))
	}
}

// TestCopyFilePreservesContentAndMode：copyFile 是 EXDEV 回落的载体，
// 内容与权限位必须原样保留。
func TestCopyFilePreservesContentAndMode(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "src.jpg")
	dst := filepath.Join(dir, "dst.jpg")
	if err := os.WriteFile(src, []byte("jpeg-bytes"), 0o640); err != nil {
		t.Fatalf("写源文件失败: %v", err)
	}
	if err := copyFile(src, dst); err != nil {
		t.Fatalf("copyFile: %v", err)
	}
	b, err := os.ReadFile(dst)
	if err != nil {
		t.Fatalf("读副本失败: %v", err)
	}
	if string(b) != "jpeg-bytes" {
		t.Fatalf("副本内容不符: %q", string(b))
	}
	srcInfo, err := os.Stat(src)
	if err != nil {
		t.Fatalf("stat 源失败: %v", err)
	}
	dstInfo, err := os.Stat(dst)
	if err != nil {
		t.Fatalf("stat 副本失败: %v", err)
	}
	if srcInfo.Mode().Perm() != dstInfo.Mode().Perm() {
		t.Fatalf("权限位不符: src=%v dst=%v", srcInfo.Mode().Perm(), dstInfo.Mode().Perm())
	}
}
