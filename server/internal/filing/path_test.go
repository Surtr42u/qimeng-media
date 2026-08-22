package filing

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

func TestNormalizeRelPathValid(t *testing.T) {
	tests := []struct {
		name string
		raw  string
		want string
	}{
		{"普通多级", "a/b/c.jpg", "a/b/c.jpg"},
		{"中文多级", "作者/作品/封面.png", "作者/作品/封面.png"},
		{"连续分隔符收敛", "a//b///c", "a/b/c"},
		{"当前目录段收敛", "./a/./b/", "a/b"},
		{"尾分隔符收敛", "a/b/", "a/b"},
		{"反斜杠统一为斜杠", `a\b\c.jpg`, "a/b/c.jpg"},
		{"两字面点前缀不是逃逸", "..hidden", "..hidden"},
		{"段中间双点", "a..b/c", "a..b/c"},
		{"百分号解码出合法子路径", "a%2Fb%2Fc.jpg", "a/b/c.jpg"},
		{"孤立百分号原样保留", "100%.jpg", "100%.jpg"},
		{"非保留名的保留名前缀", "conference/nokia.jpg", "conference/nokia.jpg"},
		{"NUL5 不是保留名（精确匹配）", "NUL5.jpg", "NUL5.jpg"},
		{"NULL 不是保留名", "NULL.jpg", "NULL.jpg"},
		{"COM10 不是保留名", "COM10.mp4", "COM10.mp4"},
		{"双重编码解一次后为无害字面量", "..%252fetc", "..%2fetc"},
		{"大写字母开头非盘符路径", "GALLERY/2026/pic.jpg", "GALLERY/2026/pic.jpg"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := NormalizeRelPath(tt.raw)
			if err != nil {
				t.Fatalf("NormalizeRelPath(%q) 意外报错: %v", tt.raw, err)
			}
			if got != tt.want {
				t.Fatalf("NormalizeRelPath(%q) = %q, want %q", tt.raw, got, tt.want)
			}
		})
	}
}

func TestNormalizeRelPathRejected(t *testing.T) {
	tests := []struct {
		name string
		raw  string
		want error
	}{
		{"空路径", "", ErrEmptyPath},
		{"纯点", ".", ErrEmptyPath},
		{"点+斜杠", "./", ErrEmptyPath},
		{"纯斜杠", "/", ErrAbsolutePath}, // 先命中绝对路径拒绝
		{"纯双反斜杠", `\\`, ErrAbsolutePath},
		{"折叠回库根", "a/..", ErrEmptyPath},
		{"点段折叠回库根", "a/b/../..", ErrEmptyPath},
		{"相对逃逸", "../etc/passwd", ErrPathEscape},
		{"裸两点", "..", ErrPathEscape},
		{"多点折叠逃逸", "foo/../../bar", ErrPathEscape},
		{"反斜杠逃逸", `..\windows\system32`, ErrPathEscape},
		{"混合分隔符逃逸", `..\../..//x`, ErrPathEscape},
		{"百分号编码逃逸", "..%2fetc%2fpasswd", ErrPathEscape},
		{"编码段逃逸", "%2e%2e/", ErrPathEscape},
		{"三段编码逃逸", "%2e%2e%2f%2e%2e%2fx", ErrPathEscape},
		{"解码成绝对路径", "%2Fetc%2Fpasswd", ErrAbsolutePath},
		{"Unix 绝对路径", "/etc/passwd", ErrAbsolutePath},
		{"Windows 盘符大写", `C:\x`, ErrAbsolutePath},
		{"Windows 盘符小写", "c:", ErrAbsolutePath},
		{"盘符+正斜杠", "D:/media/x.jpg", ErrAbsolutePath},
		{"UNC 路径", `\\server\share\x`, ErrAbsolutePath},
		{"DOS 设备路径", `\\.\C:\x`, ErrAbsolutePath},
		{"保留名 CON", "CON", ErrReservedName},
		{"保留名 con（大小写不敏感）", "con.txt", ErrReservedName},
		{"保留名 Con 带扩展", "Con.jpg", ErrReservedName},
		{"保留名 NUL 带双层扩展", "NUL.x.jpg", ErrReservedName},
		{"保留名 COM1", "COM1.mp4", ErrReservedName},
		{"保留名 lpt9", "lpt9", ErrReservedName},
		{"路径中间段保留名", "a/CON/b.jpg", ErrReservedName},
		{"目录段 AUX", "aux/file.jpg", ErrReservedName},
		{"解码出 NUL", "a%00b", ErrNulInPath},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := NormalizeRelPath(tt.raw)
			if err == nil {
				t.Fatalf("NormalizeRelPath(%q) = %q, 想要拒绝", tt.raw, got)
			}
			if !errors.Is(err, tt.want) {
				t.Fatalf("NormalizeRelPath(%q) 错误 = %v, want %v", tt.raw, err, tt.want)
			}
		})
	}
}

// TestNormalizeRelPathStaysInRoot 验证核心安全性质：任何通过的路径 join 任意库根后
// 必然仍在库根内（红线 #1 的最终保证）。
func TestNormalizeRelPathStaysInRoot(t *testing.T) {
	valid := []string{"a/b/c.jpg", "作者/作品/x.mp4", "..hidden", "NUL5.jpg", "..%252fetc"}
	roots := []string{"/data/lib", "/media", `C:\库`, "/data/media"}
	for _, raw := range valid {
		rel, err := NormalizeRelPath(raw)
		if err != nil {
			t.Fatalf("NormalizeRelPath(%q) 报错: %v", raw, err)
		}
		if strings.HasPrefix(filepath.ToSlash(rel), "/") {
			t.Fatalf("%q 规范化成了绝对路径: %q", raw, rel)
		}
		for _, root := range roots {
			joined := filepath.Join(root, filepath.FromSlash(rel))
			if !PathWithinRoot(root, joined) {
				t.Fatalf("join(%q, %q) = %q 逃出了 %q", root, rel, joined, root)
			}
		}
	}
}

func TestPathWithinRoot(t *testing.T) {
	root := filepath.Join("/", "data", "lib")
	tests := []struct {
		name string
		p    string
		want bool
	}{
		{"库根本身", root, true},
		{"库内深层文件", filepath.Join(root, "a/b/c.jpg"), true},
		{"兄弟目录前缀绕过（关键负例）", filepath.Join(root+"x", "evil.jpg"), false},
		{"逃逸出库根", filepath.Join(root, "..", "lib2", "e.jpg"), false},
		{"完全无关路径", filepath.Join("/", "etc", "passwd"), false},
		{"库根的父目录", filepath.Dir(root), false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := PathWithinRoot(root, tt.p); got != tt.want {
				t.Fatalf("PathWithinRoot(%q, %q) = %v, want %v", root, tt.p, got, tt.want)
			}
		})
	}
	if PathWithinRoot("", "/etc/passwd") {
		t.Fatal("空 root 应返回 false")
	}
}
