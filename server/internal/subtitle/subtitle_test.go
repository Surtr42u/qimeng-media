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

// TestTracksLanguagesAndPriority 覆盖多语言列举、语言段归一（zh_cn/chs → zh-Hans）、
// 同语言多格式只留优先级最高的一条、非字幕文件与大小写不一致的 stem 被忽略、
// 首选轨标记与标签。
func TestTracksLanguagesAndPriority(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "3  视频", "1  3D")
	video := filepath.Join(root, "守望先锋  DVA 39.mp4")
	subDir, err := Dir(root, video)
	if err != nil {
		t.Fatal(err)
	}
	zhAss := writeFile(t, subDir, "守望先锋  DVA 39.zh.ass", []byte("ass-bytes"))
	writeFile(t, subDir, "守望先锋  DVA 39.chs.srt", []byte("srt")) // 同一语言 zh-Hans，低优先
	enSrt := writeFile(t, subDir, "守望先锋  DVA 39.eng.srt", []byte("en-sub"))
	jaVtt := writeFile(t, subDir, "守望先锋  DVA 39.jp.vtt", []byte("ja"))
	// 以下文件不应进入结果
	writeFile(t, subDir, "其他视频.zh.ass", []byte("other"))
	writeFile(t, subDir, "守望先锋   DVA 39.zh.ass", []byte("stem-space-mismatch"))
	writeFile(t, subDir, "守望先锋  DVA 39.zh.txt", []byte("unsupported"))
	writeFile(t, subDir, "守望先锋  DVA 39..ass", nil) // 空语言段（异常命名）

	tracks, err := Tracks(root, video)
	if err != nil {
		t.Fatalf("Tracks 报错: %v", err)
	}
	if len(tracks) != 3 {
		t.Fatalf("tracks 数量 = %d, want 3: %+v", len(tracks), tracks)
	}
	want := []Track{
		{Lang: "en", Label: "English", Format: "srt", ServeFormat: "srt", Path: enSrt, SizeBytes: int64(len("en-sub"))},
		{Lang: "ja", Label: "日本語", Format: "vtt", ServeFormat: "vtt", Path: jaVtt, SizeBytes: int64(len("ja"))},
		{Lang: "zh-Hans", Label: "中文（简体）", IsDefault: true, Format: "ass", ServeFormat: "ass", Path: zhAss, SizeBytes: int64(len("ass-bytes"))},
	}
	for i := range want {
		if tracks[i] != want[i] {
			t.Fatalf("tracks[%d] = %+v, want %+v", i, tracks[i], want[i])
		}
	}
}

// TestTracksConvertedFormatsAndUnd 覆盖：无语言段 → und；转换格式的 ServeFormat
// 恒 vtt；同语言原生格式优先于转换格式；繁体中文在无简体时成为首选。
func TestTracksConvertedFormatsAndUnd(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "lib")
	video := filepath.Join(root, "v.mp4")
	subDir, _ := Dir(root, video)
	writeFile(t, subDir, "v.ass", []byte("und"))
	zhHantLrc := writeFile(t, subDir, "v.zh_tw.lrc", []byte("[00:01.00]繁体"))
	writeFile(t, subDir, "v.big5.smi", []byte("<SAMI></SAMI>")) // 同 zh-Hant，低优先
	zhHansSub := writeFile(t, subDir, "v.GB.sub", []byte("{1}{1}text"))
	writeFile(t, subDir, "v.gb.sbv", []byte("0:00:01.000,0:00:02.000\nx\n"))

	tracks, err := Tracks(root, video)
	if err != nil {
		t.Fatalf("Tracks 报错: %v", err)
	}
	if len(tracks) != 3 {
		t.Fatalf("tracks = %+v, want 3 条（und/zh-Hans/zh-Hant）", tracks)
	}
	byLang := map[string]Track{}
	for _, tr := range tracks {
		byLang[tr.Lang] = tr
	}
	if got := byLang["und"]; got.Format != "ass" || got.Label != "未标注" || got.IsDefault {
		t.Fatalf("und = %+v", got)
	}
	// .sub 优先于 .sbv（同语言 zh-Hans）；下发格式是 vtt，size 报原件。
	if got := byLang["zh-Hans"]; got.Format != "sub" || got.ServeFormat != "vtt" || !got.Converted() || !got.IsDefault || got.Path != zhHansSub {
		t.Fatalf("zh-Hans = %+v", got)
	}
	// 有简体时繁体不是首选（首选规则：简体 > 繁体 > 无）。
	if got := byLang["zh-Hant"]; got.Format != "lrc" || got.ServeFormat != "vtt" || got.IsDefault || got.Path != zhHantLrc || got.Label != "中文（繁体）" {
		t.Fatalf("zh-Hant = %+v", got)
	}
}

// TestTracksTraditionalBecomesDefault 覆盖：只有繁体中文时繁体成为首选。
func TestTracksTraditionalBecomesDefault(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "lib")
	video := filepath.Join(root, "v.mp4")
	subDir, _ := Dir(root, video)
	writeFile(t, subDir, "v.cht.srt", []byte("1"))
	tracks, err := Tracks(root, video)
	if err != nil || len(tracks) != 1 {
		t.Fatalf("tracks=%+v err=%v", tracks, err)
	}
	if tracks[0].Lang != "zh-Hant" || !tracks[0].IsDefault {
		t.Fatalf("繁体应为默认轨: %+v", tracks[0])
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

// TestResolve 覆盖按语言取用（别名/大小写/下划线），未知语言与路径穿越形态的语言码。
func TestResolve(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "lib")
	video := filepath.Join(root, "v.mp4")
	subDir, _ := Dir(root, video)
	zh := writeFile(t, subDir, "v.zh.ass", []byte("zh"))

	for _, q := range []string{"zh", "ZH", "zh_cn", "chs", "gb", "zh-Hans"} {
		got, ok, err := Resolve(root, video, q)
		if err != nil || !ok {
			t.Fatalf("Resolve(%q) ok=%v err=%v", q, ok, err)
		}
		if got.Path != zh {
			t.Fatalf("Resolve(%q).Path = %q, want %q", q, got.Path, zh)
		}
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
