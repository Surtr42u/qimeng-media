package filing

// MoveFile 组件测试：rename 快路径与 copyFile 副本正确性。
//
// EXDEV 回落分支无法在单机单卷的单测环境里稳定复现（需要真实的两个
// 挂载点），该分支的行为由 fnOS 虚拟机 Docker 部署的端到端删除/恢复
// 链路验证（deploy/README.md 实测记录：/media 与 /data 两个卷之间
// 删除→回收站→恢复）。

import (
	"errors"
	"fmt"
	"io"
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

// TestCopyFileFailureCleansDst：复制中途失败 → 报错、源文件原状、目标端
// 半截副本被清理（F2 根修：不留幽灵残缺件）。直接测 copyFile 本体——
// EXDEV 回落的完整 MoveFile 路径需真实双挂载点（见文件头注），目标端
// 清理逻辑落在 copyFile 内，替身 copyBody 注入失败即可覆盖。
// 失败注入靠包内替身 copyBody（磁盘满/写失败无法用真实文件系统稳定复现）。
func TestCopyFileFailureCleansDst(t *testing.T) {
	cases := []struct {
		name string
		stub func(dst string, srcB []byte) error
	}{
		{"复制中途失败（半截）", func(dst string, srcB []byte) error {
			if err := os.WriteFile(dst, srcB[:len(srcB)/2], 0o644); err != nil {
				return err
			}
			return errors.New("注入的复制中断")
		}},
		{"全部字节已写但收尾失败", func(dst string, srcB []byte) error {
			// io.Copy 的病态形态：字节全写完仍报错——此时副本"疑似完整"，
			// 但 copyFile 侧明知复制失败，清理不依赖大小猜测。
			if err := os.WriteFile(dst, srcB, 0o644); err != nil {
				return err
			}
			return errors.New("注入的收尾失败")
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			src := filepath.Join(dir, "src.mp4")
			dst := filepath.Join(dir, "dst.mp4")
			srcB := []byte("0123456789abcdef")
			if err := os.WriteFile(src, srcB, 0o644); err != nil {
				t.Fatalf("写源文件失败: %v", err)
			}
			orig := copyBody
			copyBody = func(_ io.Writer, _ io.Reader) (int64, error) {
				return 0, tc.stub(dst, srcB)
			}
			t.Cleanup(func() { copyBody = orig })

			if err := copyFile(src, dst); err == nil {
				t.Fatal("copyFile 应上抛注入的复制失败")
			}
			if b, err := os.ReadFile(src); err != nil || string(b) != string(srcB) {
				t.Fatalf("源文件必须原状未动: err=%v b=%q", err, b)
			}
			if _, err := os.Stat(dst); !os.IsNotExist(err) {
				t.Fatalf("目标端半截副本应被清理, stat err=%v", err)
			}
		})
	}
}

// TestRemovePartialCopy：只删严格小于源的确定残缺副本；疑似完整
// （大小相等，"复制完整但删源失败"的形态）宁留勿删；目标不存在=已清理。
func TestRemovePartialCopy(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "src")
	if err := os.WriteFile(src, []byte("0123456789"), 0o644); err != nil {
		t.Fatalf("写源失败: %v", err)
	}

	t.Run("半截副本被删", func(t *testing.T) {
		dst := filepath.Join(dir, "partial")
		if err := os.WriteFile(dst, []byte("01234"), 0o644); err != nil {
			t.Fatalf("写半截失败: %v", err)
		}
		if err := RemovePartialCopy(src, dst); err != nil {
			t.Fatalf("RemovePartialCopy: %v", err)
		}
		if _, err := os.Stat(dst); !os.IsNotExist(err) {
			t.Fatalf("半截副本应被删除, stat err=%v", err)
		}
	})
	t.Run("疑似完整副本保留", func(t *testing.T) {
		dst := filepath.Join(dir, "complete")
		if err := os.WriteFile(dst, []byte("0123456789"), 0o644); err != nil {
			t.Fatalf("写等大小副本失败: %v", err)
		}
		if err := RemovePartialCopy(src, dst); err != nil {
			t.Fatalf("RemovePartialCopy: %v", err)
		}
		if _, err := os.Stat(dst); err != nil {
			t.Fatalf("疑似完整副本不能删（删源失败形态）, stat err=%v", err)
		}
	})
	t.Run("目标不存在视为已清理", func(t *testing.T) {
		dst := filepath.Join(dir, "absent")
		if err := RemovePartialCopy(src, dst); err != nil {
			t.Fatalf("RemovePartialCopy: %v", err)
		}
	})
}

// TestIsCrossDeviceErr：跨挂载点判定（errno 主判分平台实现，真实 EXDEV 无法在
// 单卷单测稳定复现）——文本兜底直接锁定 2026-10-11 事故回归：Windows 双盘布局
// 下 os.Rename 的文本 "different disk drive" 原口径漏判，导致删除（移入回收站）
// 与本机同步搬移全部失败且不走复制回落。Linux 文本、大小写、包装层同锁；
// 无关错误不得误判（误判会把普通 rename 失败卷入复制回落）。
func TestIsCrossDeviceErr(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"linux 文本（原始口径）", errors.New("rename /a /b: invalid cross-device link"), true},
		{"windows 文本（2026-10-11 事故原样）", errors.New(`rename C:\a\x.mp4 D:\b\x.mp4: The system cannot move the file to a different disk drive.`), true},
		{"windows 文本小写", errors.New("the system cannot move the file to a different disk drive"), true},
		{"包装错误穿透", fmt.Errorf("移入回收站: %w", errors.New("invalid cross-device link")), true},
		{"无关错误不误判", errors.New("permission denied"), false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := isCrossDeviceErr(c.err); got != c.want {
				t.Fatalf("isCrossDeviceErr(%v) = %v, want %v", c.err, got, c.want)
			}
		})
	}
}
