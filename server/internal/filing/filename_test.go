package filing

import (
	"errors"
	"strconv"
	"testing"
)

func TestSanitizeFilename(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		{"普通名字", "photo.jpg", "photo.jpg"},
		{"剥离正斜杠", "a/b.jpg", "ab.jpg"},
		{"剥离反斜杠", `a\b.png`, "ab.png"},
		{"剥离全部非法字符", `a:b*c?d"e<f>g|h`, "abcdefgh"},
		{"剥离控制字符", "a\x01b\x1Fc", "abc"},
		{"剥离 DEL", "a\x7Fb", "ab"},
		{"剥离首尾空格", "  space.jpg  ", "space.jpg"},
		{"剥离尾点", "trailing.jpg.", "trailing.jpg"},
		{"剥离首点与尾点空格", ". lead.jpg . ", "lead.jpg"},
		{"中文名保留", "中文文件名.jpg", "中文文件名.jpg"},
		{"emoji 保留", "🎬电影.mp4", "🎬电影.mp4"},
		{"日文名保留", "なまえ.png", "なまえ.png"},
		{"合法符号保留", "movie[2020] (1080p).mp4", "movie[2020] (1080p).mp4"},
		{"手机待测文件名-带点与空格", "D.Va  No.01.mp4", "D.Va  No.01.mp4"},
		{"手机待测文件名-全角括号与中文", "11（测试）.mp4", "11（测试）.mp4"},
		{"手机待测文件名-基名尾部空格保留", "Idemi .mp4", "Idemi .mp4"},
		{"手机待测文件名-中点符号保留", "10·测试·.mp4", "10·测试·.mp4"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := SanitizeFilename(tt.in)
			if err != nil {
				t.Fatalf("SanitizeFilename(%q) 意外报错: %v", tt.in, err)
			}
			if got != tt.want {
				t.Fatalf("SanitizeFilename(%q) = %q, want %q", tt.in, got, tt.want)
			}
		})
	}
}

func TestSanitizeFilenameErrors(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want error
	}{
		{"空名", "", ErrFilenameEmpty},
		{"全非法字符", "???", ErrFilenameEmpty},
		{"全分隔符", "///", ErrFilenameEmpty},
		{"全控制字符", "\x01\x02\x1F", ErrFilenameEmpty},
		{"只剩点与空格", " . . ", ErrFilenameEmpty},
		{"保留名 CON", "CON", ErrFilenameReserved},
		{"保留名 con 带扩展", "con.mp4", ErrFilenameReserved},
		{"保留名 NUL", "NUL.jpg", ErrFilenameReserved},
		{"保留名 COM3", "COM3", ErrFilenameReserved},
		{"保留名清洗后残留", "CO/N", ErrFilenameReserved}, // 剥离 / 后变成 CON
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := SanitizeFilename(tt.in)
			if err == nil {
				t.Fatalf("SanitizeFilename(%q) = %q, 想要报错", tt.in, got)
			}
			if !errors.Is(err, tt.want) {
				t.Fatalf("SanitizeFilename(%q) 错误 = %v, want %v", tt.in, err, tt.want)
			}
		})
	}
}

func TestResolveConflict(t *testing.T) {
	t.Run("不冲突返回原名", func(t *testing.T) {
		if got := ResolveConflict("photo.jpg", func(string) bool { return false }); got != "photo.jpg" {
			t.Fatalf("got %q", got)
		}
	})
	t.Run("第一次冲突取 2", func(t *testing.T) {
		exists := func(s string) bool { return s == "photo.jpg" }
		if got := ResolveConflict("photo.jpg", exists); got != "photo (2).jpg" {
			t.Fatalf("got %q, want %q", got, "photo (2).jpg")
		}
	})
	t.Run("2 与 3 均被占用则取 4", func(t *testing.T) {
		exists := func(s string) bool {
			return s == "photo.jpg" || s == "photo (2).jpg" || s == "photo (3).jpg"
		}
		if got := ResolveConflict("photo.jpg", exists); got != "photo (4).jpg" {
			t.Fatalf("got %q", got)
		}
	})
	t.Run("连续占用递增到 10", func(t *testing.T) {
		exists := func(s string) bool {
			if s == "photo.jpg" {
				return true
			}
			for i := 2; i <= 9; i++ {
				if s == "photo ("+strconv.Itoa(i)+").jpg" {
					return true
				}
			}
			return false
		}
		if got := ResolveConflict("photo.jpg", exists); got != "photo (10).jpg" {
			t.Fatalf("got %q, want %q", got, "photo (10).jpg")
		}
	})
	t.Run("无扩展名", func(t *testing.T) {
		exists := func(s string) bool { return s == "movie" }
		if got := ResolveConflict("movie", exists); got != "movie (2)" {
			t.Fatalf("got %q", got)
		}
	})
	t.Run("隐藏文件形态按无扩展名处理", func(t *testing.T) {
		exists := func(s string) bool { return s == ".gitignore" }
		if got := ResolveConflict(".gitignore", exists); got != ".gitignore (2)" {
			t.Fatalf("got %q", got)
		}
	})
	t.Run("中文名冲突", func(t *testing.T) {
		exists := func(s string) bool { return s == "电影.mp4" }
		if got := ResolveConflict("电影.mp4", exists); got != "电影 (2).mp4" {
			t.Fatalf("got %q", got)
		}
	})
	t.Run("exists 为 nil 返回原名", func(t *testing.T) {
		if got := ResolveConflict("a.jpg", nil); got != "a.jpg" {
			t.Fatalf("got %q", got)
		}
	})
	t.Run("长序号候选不引入路径语义字符", func(t *testing.T) {
		exists := func(s string) bool { return s != "x (1000).jpg" } // 占用前 999 个候选
		if got := ResolveConflict("x.jpg", exists); got != "x (1000).jpg" {
			t.Fatalf("got %q", got)
		}
	})
}
