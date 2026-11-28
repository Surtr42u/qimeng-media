package httpapi

import (
	"bytes"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// writeSubtitle 按约定布局写一份外挂字幕：<库根父目录>/5  字幕/<库文件夹名>/<相对目录>。
// 库根 = env.media（末尾段即库文件夹名），字面量「5  字幕」在此显式写出——
// 目录名（数字 + 两个空格 + 字幕）是用户既有磁盘布局，测试必须锁住逐字节形态。
func (e *testEnv) writeSubtitle(t *testing.T, relName string, content []byte) string {
	t.Helper()
	p := filepath.Join(filepath.Dir(e.media), "5  字幕", filepath.Base(e.media), filepath.FromSlash(relName))
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatalf("建字幕目录失败: %v", err)
	}
	if err := os.WriteFile(p, content, 0o644); err != nil {
		t.Fatalf("写字幕失败: %v", err)
	}
	return p
}

const (
	testSubtitleASSText = "[Script Info]\nTitle: 测试\n\n[Events]\nDialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,你好\n"
	testSubtitleSRTText = "1\n00:00:00,000 --> 00:00:02,000\nhello\n"
)

// TestAssetDetailSubtitlesAndServing 覆盖：详情 subtitles 数组（语言规范化为
// BCP 47、label/isDefault、格式/大小/签名直链）、按语言别名取用、Range 分片、
// Content-Type、同语言多格式去重。
func TestAssetDetailSubtitlesAndServing(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "c.mp4")
	if !ok {
		t.Fatal("合成视频缺失")
	}
	ass := []byte(testSubtitleASSText)
	env.writeSubtitle(t, "c.zh.ass", ass)
	// 同语言低优先格式（chs 也归并为 zh-Hans）：不应出现在响应里，也不应被选中。
	env.writeSubtitle(t, "c.chs.srt", []byte(testSubtitleSRTText))
	env.writeSubtitle(t, "c.eng.srt", []byte(testSubtitleSRTText))
	// 非字幕文件与不匹配主干：忽略。
	env.writeSubtitle(t, "c.zh.nfo", []byte("nfo"))
	env.writeSubtitle(t, "d.zh.ass", []byte("other"))

	d := env.detail(t, id)
	if d.Subtitles == nil || len(*d.Subtitles) != 2 {
		t.Fatalf("subtitles = %+v, want 2 条（en/zh-Hans）", d.Subtitles)
	}
	subs := *d.Subtitles
	if subs[0].Lang != "en" || subs[0].Format != gen.Srt || subs[0].Label != "English" || subs[0].IsDefault ||
		subs[0].SizeBytes != int64(len(testSubtitleSRTText)) {
		t.Fatalf("subtitles[0] = %+v", subs[0])
	}
	if subs[1].Lang != "zh-Hans" || subs[1].Format != gen.Ass || subs[1].Label != "中文（简体）" ||
		!subs[1].IsDefault || subs[1].SizeBytes != int64(len(ass)) {
		t.Fatalf("subtitles[1] = %+v（同语言应选 ass 且为默认轨）", subs[1])
	}

	for _, c := range []struct {
		name, url, contentType string
		want                   []byte
	}{
		{"ass 全量", subs[1].Url, "text/x-ssa; charset=utf-8", ass},
		{"srt 全量", subs[0].Url, "application/x-subrip; charset=utf-8", []byte(testSubtitleSRTText)},
	} {
		resp, err := http.Get(env.ts.URL + c.url)
		if err != nil {
			t.Fatalf("%s: 请求失败: %v", c.name, err)
		}
		got := readAll(t, resp)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("%s: 状态 %d", c.name, resp.StatusCode)
		}
		if !bytes.Equal(got, c.want) {
			t.Fatalf("%s: 内容不符 %q", c.name, got)
		}
		if ct := resp.Header.Get("Content-Type"); ct != c.contentType {
			t.Fatalf("%s: Content-Type = %q, want %q", c.name, ct, c.contentType)
		}
	}

	// Range：播放器拖动/断点续读依赖 206 + 精确分片。
	req, err := http.NewRequest(http.MethodGet, env.ts.URL+subs[1].Url, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Range", "bytes=0-9")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("Range 状态 = %d, want 206", resp.StatusCode)
	}
	if got := readAll(t, resp); !bytes.Equal(got, ass[:10]) {
		t.Fatalf("Range 内容 = %q, want %q", got, ass[:10])
	}

	// 语言码别名/大小写统一归一到 zh-Hans；签名只锚定路径，改 lang 仍通过验签。
	for _, alias := range []string{"ZH", "zh_cn", "chs", "GB"} {
		aliasURL := strings.Replace(subs[1].Url, "lang=zh-Hans", "lang="+alias, 1)
		resp, err := http.Get(env.ts.URL + aliasURL)
		if err != nil {
			t.Fatalf("语言别名 %q 请求失败: %v", alias, err)
		}
		closeBody(resp)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("语言别名 %q 状态 %d, want 200", alias, resp.StatusCode)
		}
	}
}

// TestAssetSubtitlesAbsent 覆盖：无字幕目录时详情缺省字段、图片资产不列举、
// 未知语言/穿越形态语言码 404、缺签名 403（与 /media/orig 同款鉴权）。
func TestAssetSubtitlesAbsent(t *testing.T) {
	env := newTestEnv(t)
	vid, ok := env.assetIDByName(t, "c.mp4")
	if !ok {
		t.Fatal("合成视频缺失")
	}
	img, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("合成图片缺失")
	}
	// 给图片写一份「同名」字幕：图片资产不应列举字幕（约定只覆盖视频）。
	env.writeSubtitle(t, "a.zh.ass", []byte("img"))

	if d := env.detail(t, vid); d.Subtitles != nil {
		t.Fatalf("无字幕目录时 subtitles = %+v, want nil", *d.Subtitles)
	}
	if d := env.detail(t, img); d.Subtitles != nil {
		t.Fatalf("图片资产 subtitles = %+v, want nil", *d.Subtitles)
	}

	env.writeSubtitle(t, "c.zh.ass", []byte("sub"))
	d := env.detail(t, vid)
	if d.Subtitles == nil || len(*d.Subtitles) != 1 {
		t.Fatalf("subtitles = %+v, want 1 条", d.Subtitles)
	}
	good := (*d.Subtitles)[0].Url
	for _, c := range []struct {
		name string
		url  string
		want int
	}{
		{"未知语言", strings.Replace(good, "lang=zh-Hans", "lang=ja", 1), http.StatusNotFound},
		{"穿越形态语言码", strings.Replace(good, "lang=zh-Hans", "lang="+url.QueryEscape("../../etc/passwd"), 1), http.StatusNotFound},
		{"缺签名参数", "/media/sub/" + vid + "?lang=zh", http.StatusForbidden},
	} {
		resp, err := http.Get(env.ts.URL + c.url)
		if err != nil {
			t.Fatalf("%s: 请求失败: %v", c.name, err)
		}
		closeBody(resp)
		if resp.StatusCode != c.want {
			t.Fatalf("%s: 状态 %d, want %d", c.name, resp.StatusCode, c.want)
		}
	}
}

// TestSubtitleEncodingAndConversion 覆盖：GB18030 源文件按 UTF-8 下发；lrc 源文件
// 服务端转 VTT 下发（协议 format=vtt、Content-Type text/vtt、无语言段=und）。
func TestSubtitleEncodingAndConversion(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "c.mp4")
	if !ok {
		t.Fatal("合成视频缺失")
	}
	// GB18030「中文」：D6 D0 CE C4（Windows 记事本常见编码形态）
	env.writeSubtitle(t, "c.zh.ass", []byte{0xD6, 0xD0, 0xCE, 0xC4, '\n'})
	env.writeSubtitle(t, "c.ja.lrc", []byte("[00:01.00]こんにちは\n"))
	env.writeSubtitle(t, "c.ass", []byte("und-track"))

	d := env.detail(t, id)
	if d.Subtitles == nil || len(*d.Subtitles) != 3 {
		t.Fatalf("subtitles = %+v, want 3 条", d.Subtitles)
	}
	byLang := map[string]gen.AssetSubtitle{}
	for _, sub := range *d.Subtitles {
		byLang[sub.Lang] = sub
	}
	zh, ja, und := byLang["zh-Hans"], byLang["ja"], byLang["und"]
	if zh.Format != gen.Ass || !zh.IsDefault {
		t.Fatalf("zh = %+v", zh)
	}
	if ja.Format != gen.Vtt || ja.Label != "日本語" || ja.IsDefault {
		t.Fatalf("ja = %+v", ja)
	}
	if und.Format != gen.Ass || und.Label != "未标注" || und.IsDefault {
		t.Fatalf("und = %+v", und)
	}

	resp, err := http.Get(env.ts.URL + zh.Url)
	if err != nil {
		t.Fatal(err)
	}
	if body := readAll(t, resp); string(body) != "中文\n" {
		t.Fatalf("GB18030 应归一为 UTF-8，得到 %q", body)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "text/x-ssa; charset=utf-8" {
		t.Fatalf("ass Content-Type = %q", ct)
	}

	resp, err = http.Get(env.ts.URL + ja.Url)
	if err != nil {
		t.Fatal(err)
	}
	body := readAll(t, resp)
	if !strings.HasPrefix(string(body), "WEBVTT\n\n") || !strings.Contains(string(body), "00:00:01.000 --> 00:00:06.000") {
		t.Fatalf("lrc 应转成 VTT，得到 %q", body)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "text/vtt; charset=utf-8" {
		t.Fatalf("lrc Content-Type = %q", ct)
	}
}
