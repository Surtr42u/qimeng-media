package filing

import (
	"bytes"
	"crypto/sha256"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 显式 opt-in，只操作两处新建临时目录。先用真实 rename 证明跨卷，不能用
// 不同路径冒充不同卷；CI 未提供第二卷时明确跳过，不能据此写成部署已验收。
func TestMoveFileRealCrossVolume(t *testing.T) {
	root := os.Getenv("QIMENG_TEST_CROSS_VOLUME_ROOT")
	if root == "" {
		t.Skip("需显式提供第二卷临时根 QIMENG_TEST_CROSS_VOLUME_ROOT")
	}
	absolute, err := filepath.Abs(root)
	if err != nil {
		t.Fatal(err)
	}
	destDir, err := os.MkdirTemp(absolute, "qimeng-cross-volume-")
	if err != nil {
		t.Fatal(err)
	}
	rel, err := filepath.Rel(absolute, destDir)
	if err != nil || strings.HasPrefix(rel, "..") {
		t.Fatal("临时目录越界")
	}
	t.Cleanup(func() {
		if err := os.RemoveAll(destDir); err != nil {
			t.Error(err)
		}
	})
	sourceDir := t.TempDir()
	probe := filepath.Join(sourceDir, "probe")
	if err := os.WriteFile(probe, []byte("probe"), 0o600); err != nil {
		t.Fatal(err)
	}
	err = os.Rename(probe, filepath.Join(destDir, "probe"))
	if err == nil {
		t.Skip("提供的路径与默认临时目录位于同一卷")
	}
	if !isCrossDeviceErr(err) {
		t.Fatalf("无法确认跨卷 errno: %v", err)
	}

	payload := bytes.Repeat([]byte("synthetic-recovery-data"), 4096)
	stamp := time.Date(2025, 1, 2, 3, 4, 5, 0, time.UTC)
	makeSource := func(name string) string {
		path := filepath.Join(sourceDir, name)
		if err := os.WriteFile(path, payload, 0o600); err != nil {
			t.Fatal(err)
		}
		if err := os.Chtimes(path, stamp, stamp); err != nil {
			t.Fatal(err)
		}
		return path
	}
	t.Run("success_preserves_bytes_and_mtime", func(t *testing.T) {
		src := makeSource("success.dat")
		dst := filepath.Join(destDir, "success.dat")
		if err := MoveFile(src, dst); err != nil {
			t.Fatal(err)
		}
		got, err := os.ReadFile(dst)
		if err != nil {
			t.Fatal(err)
		}
		if sha256.Sum256(got) != sha256.Sum256(payload) {
			t.Fatal("复制字节不一致")
		}
		info, err := os.Stat(dst)
		if err != nil {
			t.Fatal(err)
		}
		if !info.ModTime().Equal(stamp) {
			t.Fatal("mtime 未保留")
		}
		if _, err := os.Stat(src); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("成功后源仍在")
		}
	})
	t.Run("existing_target_is_untouched", func(t *testing.T) {
		src := makeSource("conflict.dat")
		dst := filepath.Join(destDir, "conflict.dat")
		if err := os.WriteFile(dst, []byte("existing"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := MoveFile(src, dst); !errors.Is(err, ErrMoveTargetExists) {
			t.Fatalf("err=%v", err)
		}
		got, err := os.ReadFile(dst)
		if err != nil || string(got) != "existing" {
			t.Fatal("既有目标被破坏")
		}
		if _, err := os.Stat(src); err != nil {
			t.Fatal("源文件被破坏")
		}
	})
	t.Run("copy_failure_keeps_source", func(t *testing.T) {
		src := makeSource("failure.dat")
		dst := filepath.Join(destDir, "failure.dat")
		before := copyBody
		copyBody = func(w io.Writer, r io.Reader) (int64, error) {
			n, err := io.CopyN(w, r, 32)
			if err != nil {
				return n, err
			}
			return n, errors.New("synthetic interrupted copy")
		}
		t.Cleanup(func() { copyBody = before })
		if err := MoveFile(src, dst); err == nil {
			t.Fatal("应报告失败")
		}
		got, err := os.ReadFile(src)
		if err != nil || !bytes.Equal(got, payload) {
			t.Fatal("失败后源不完整")
		}
		if _, err := os.Stat(dst); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("半截副本未清理")
		}
	})
}
