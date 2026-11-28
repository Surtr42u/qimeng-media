package subtitle

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// writeFile 在 dir 下建文件（含中间目录），内容只需可读，长度用于 sizeBytes 断言。
func writeFile(t *testing.T, dir, name string, content []byte) string {
	t.Helper()
	p := filepath.Join(dir, name)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	if err := os.WriteFile(p, content, 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}
	return p
}

// TestDir 覆盖约定路径推导：库根含空格、资产名含空格/+ /括号、资产在子目录、
// 以及库根父目录即「5  字幕」所在层的镜像语义。
func TestDir(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "3  视频", "1  3D")
	cases := []struct {
		name string
		rel  string
		want string
	}{
		{
			name: "含空格与中文的顶层文件名",
			rel:  "守望先锋  DVA 39.mp4",
			want: filepath.Join(base, "3  视频", "5  字幕", "1  3D"),
		},
		{
			name: "子目录镜像",
			rel:  filepath.Join("合集 (1)", "A+B 手办.png"),
			want: filepath.Join(base, "3  视频", "5  字幕", "1  3D", "合集 (1)"),
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := Dir(root, filepath.Join(root, c.rel))
			if err != nil {
				t.Fatalf("Dir 报错: %v", err)
			}
			if got != c.want {
				t.Fatalf("Dir = %q, want %q", got, c.want)
			}
		})
	}
}

// TestTracksLanguagesAndPriority 覆盖多语言列举、同语言多格式只留优先级最高
// 的一条、非字幕文件与大小写不一致的 stem 被忽略、语言码排序。
func TestTracksLanguagesAndPriority(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "3  视频", "1  3D")
	video := filepath.Join(root, "守望先锋  DVA 39.mp4")
	subDir, err := Dir(root, video)
	if err != nil {
		t.Fatal(err)
	}
	zhAss := writeFile(t, subDir, "守望先锋  DVA 39.zh.ass", []byte("ass-bytes"))
	writeFile(t, subDir, "守望先锋  DVA 39.zh.srt", []byte("srt"))
	enSrt := writeFile(t, subDir, "守望先锋  DVA 39.en.srt", []byte("en-sub"))
	jaVtt := writeFile(t, subDir, "守望先锋  DVA 39.ja.vtt", []byte("ja"))
	// 以下文件不应进入结果
	writeFile(t, subDir, "其他视频.zh.ass", []byte("other"))
	writeFile(t, subDir, "守望先锋   DVA 39.zh.ass", []byte("stem-space-mismatch"))
	writeFile(t, subDir, "守望先锋  DVA 39.zh.txt", []byte("unsupported"))
	writeFile(t, subDir, "守望先锋  DVA 39.ass", nil) // 无语言码

	tracks, err := Tracks(root, video)
	if err != nil {
		t.Fatalf("Tracks 报错: %v", err)
	}
	if len(tracks) != 3 {
		t.Fatalf("tracks 数量 = %d, want 3: %+v", len(tracks), tracks)
	}
	want := []Track{
		{Lang: "en", Format: "srt", Path: enSrt, SizeBytes: int64(len("en-sub"))},
		{Lang: "ja", Format: "vtt", Path: jaVtt, SizeBytes: int64(len("ja"))},
		{Lang: "zh", Format: "ass", Path: zhAss, SizeBytes: int64(len("ass-bytes"))},
	}
	for i := range want {
		if tracks[i] != want[i] {
			t.Fatalf("tracks[%d] = %+v, want %+v", i, tracks[i], want[i])
		}
	}
}

// TestTracksMissingDir 覆盖零字幕常态：字幕目录/文件不存在返回空列表且不报错。
func TestTracksMissingDir(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "lib")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	tracks, err := Tracks(root, filepath.Join(root, "a.mp4"))
	if err != nil {
		t.Fatalf("缺目录不应报错: %v", err)
	}
	if len(tracks) != 0 {
		t.Fatalf("缺目录应返回空列表，得到 %+v", tracks)
	}
	// 目录存在但无匹配文件
	subDir, _ := Dir(root, filepath.Join(root, "a.mp4"))
	writeFile(t, subDir, "b.zh.ass", []byte("x"))
	tracks, err = Tracks(root, filepath.Join(root, "a.mp4"))
	if err != nil || len(tracks) != 0 {
		t.Fatalf("无匹配文件应返回空列表: tracks=%+v err=%v", tracks, err)
	}
}

// TestResolve 覆盖按语言取用（大小写不敏感）、未知语言与路径穿越形态的语言码。
func TestResolve(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "lib")
	video := filepath.Join(root, "v.mp4")
	subDir, _ := Dir(root, video)
	zh := writeFile(t, subDir, "v.zh.ass", []byte("zh"))

	got, ok, err := Resolve(root, video, "ZH")
	if err != nil || !ok {
		t.Fatalf("Resolve(zh) ok=%v err=%v", ok, err)
	}
	if got.Path != zh {
		t.Fatalf("Resolve(zh).Path = %q, want %q", got.Path, zh)
	}
	for _, lang := range []string{"", "en", "../../etc/passwd"} {
		if _, ok, err := Resolve(root, video, lang); err != nil || ok {
			t.Fatalf("Resolve(%q) 应未命中: ok=%v err=%v", lang, ok, err)
		}
	}
}

// TestDirRejectsOutsideLibrary 覆盖资产路径越出库根（数据污染）时的拒绝路径：
// 字幕目录由相对路径推导，越界路径不允许静默算出库外目录。
func TestDirRejectsOutsideLibrary(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "lib")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	for _, abs := range []string{
		filepath.Join(base, "other", "v.mp4"),
		filepath.Join(root, "..", "v.mp4"),
		root,
	} {
		if _, err := Dir(root, abs); !errors.Is(err, ErrOutsideLibrary) {
			t.Fatalf("Dir(%q) 应返回 ErrOutsideLibrary，得到 %v", abs, err)
		}
	}
	if _, err := Dir("", filepath.Join(base, "v.mp4")); !errors.Is(err, ErrNoLibraryRoot) {
		t.Fatalf("空库根应返回 ErrNoLibraryRoot，得到 %v", err)
	}
}
