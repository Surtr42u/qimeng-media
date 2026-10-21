package filing

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestRenameInRootPreservesExistingTargets(t *testing.T) {
	for _, kind := range []string{"file", "directory", "dangling-link"} {
		t.Run(kind, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, "source"), []byte("original"), 0o600); err != nil {
				t.Fatal(err)
			}
			target := filepath.Join(dir, "target")
			switch kind {
			case "file":
				if err := os.WriteFile(target, []byte("existing"), 0o600); err != nil {
					t.Fatal(err)
				}
			case "directory":
				if err := os.Mkdir(target, 0o700); err != nil {
					t.Fatal(err)
				}
			case "dangling-link":
				if err := os.Symlink("missing", target); err != nil {
					t.Skipf("当前环境无法创建符号链接: %v", err)
				}
			}
			root, err := os.OpenRoot(dir)
			if err != nil {
				t.Fatal(err)
			}
			defer CloseRoot(root)
			if err := RenameInRoot(root, "source", "target"); !errors.Is(err, ErrMoveTargetExists) {
				t.Fatalf("应拒绝既有目标: %v", err)
			}
			if data, err := os.ReadFile(filepath.Join(dir, "source")); err != nil || string(data) != "original" {
				t.Fatal("源被改动", err)
			}
			if kind == "file" {
				if data, err := os.ReadFile(target); err != nil || string(data) != "existing" {
					t.Fatal("既有目标被覆盖", err)
				}
			} else if _, err := os.Lstat(target); err != nil {
				t.Fatal("目录/悬空链接被删除", err)
			}
		})
	}
}

func TestRenameInRootSuccessAndEscape(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "source"), []byte("original"), 0o600); err != nil {
		t.Fatal(err)
	}
	root, err := os.OpenRoot(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer CloseRoot(root)
	if err := RenameInRoot(root, "source", "../outside"); !errors.Is(err, ErrMoveDidNotCreateTarget) {
		t.Fatalf("应拒绝越界: %v", err)
	}
	if err := RenameInRoot(root, "source", "target"); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(filepath.Join(dir, "target")); err != nil || string(data) != "original" {
		t.Fatal("根内改名未保留原件", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "source")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("原路径未移走", err)
	}
}
